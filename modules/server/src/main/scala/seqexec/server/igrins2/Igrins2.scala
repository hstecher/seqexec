// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package seqexec.server.igrins2

import cats.data.Kleisli
import cats.MonadError
import cats.effect.Sync
import cats.syntax.all._
import fs2.Stream
import edu.gemini.spModel.gemini.igrins2.{ Igrins2 => SPIgrins2 }
import java.lang.{ Double => JDouble }
import org.typelevel.log4cats.Logger
import lucuma.core.enums.LightSinkName
import seqexec.model.enum.Instrument
import seqexec.model.enum.ObserveCommandResult
import seqexec.server._
import seqexec.server.keywords.GdsClient
import seqexec.server.keywords.GdsInstrument
import seqexec.server.keywords.KeywordsClient
import seqexec.server.keywords.DhsClient
import seqexec.server.keywords.DhsClientProvider
import edu.gemini.spModel.obscomp.InstConstants
import squants.time.Seconds
import squants.time.Time
import cats.effect.Async
import seqexec.model.dhs.ImageFileId
import seqexec.model.Observation
import giapi.client.commands.Configuration
import squants.time.TimeConversions._
import seqexec.server.ConfigUtilOps._
import seqexec.server.tcs.Tcs._
import cats.data.EitherT
import seqexec.model.ObserveStage
import scala.collection.concurrent.TrieMap
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

// Object to manage cached data labels for IGRINS
object Igrins2DataLabelCache {
  // Cache of data labels by observation ID
  private val cache: TrieMap[Observation.Id, ConcurrentLinkedQueue[ImageFileId]] = TrieMap.empty
  
  // Add a list of data labels to the cache for an observation
  def add(obsId: Observation.Id, labels: List[ImageFileId]): Unit = {
    val queue = new ConcurrentLinkedQueue[ImageFileId](labels.asJava)
    cache.put(obsId, queue)
  }
  
  // Get the next data label for an observation, removing it from the cache
  def getNext(obsId: Observation.Id): Option[ImageFileId] = 
    cache.get(obsId).flatMap(queue => Option(queue.poll()))
  
  // Clear cache for an observation
  def clear(obsId: Observation.Id): Unit = cache.remove(obsId)
  
  // Check if we have labels cached for an observation
  def hasLabels(obsId: Observation.Id): Boolean =
    cache.get(obsId).exists(!_.isEmpty)
}

final case class Igrins2[F[_]: Logger: Async](
  controller: Igrins2Controller[F],
  dhsClientProvider: DhsClientProvider[F]
) extends GdsInstrument[F]
    with InstrumentSystem[F] {

  override val gdsClient: GdsClient[F] = controller.gdsClient

  override val keywordsClient: KeywordsClient[F] = this

  override val resource: Instrument = Instrument.Igrins2

  override val contributorName: String = "igrins2"

  val readoutOverhead: Time = Seconds(120)

  val abort: F[Unit] = controller.abort

  // Pre-request and cache all data labels needed for a sequence
  def preRequestDataLabels(obsId: Observation.Id, stepCount: Int): F[Unit] = {
    val dhsClient = dhsClientProvider.dhsClient("")
    
    Logger[F].info(s"Pre-requesting $stepCount data labels for IGRINS sequence $obsId") *>
      (0 until stepCount).toList.traverse_ { _ => 
        dhsClient.createImage(
          DhsClient.ImageParameters(DhsClient.Permanent, List(contributorName, "dhs-http"))
        ).flatMap(label => 
          Logger[F].debug(s"Requested data label: $label for IGRINS sequence $obsId") *>
            Sync[F].delay(Igrins2DataLabelCache.add(obsId, List(label)))
        )
      } *>
      Logger[F].info(s"Completed pre-requesting data labels for IGRINS sequence $obsId")
  }

  // Get the next data label from cache or request a new one if needed
  def getDataLabel(obsId: Observation.Id): F[ImageFileId] = 
    Sync[F].delay(Igrins2DataLabelCache.getNext(obsId)).flatMap {
      case Some(label) => 
        Logger[F].debug(s"Using pre-requested data label $label for IGRINS") *> 
          Sync[F].pure(label)
      case None => 
        Logger[F].debug(s"No pre-requested label available, requesting new one from DHS") *>
          dhsClientProvider.dhsClient("").createImage(
            DhsClient.ImageParameters(DhsClient.Permanent, List(contributorName, "dhs-http"))
          )
    }

  def sequenceComplete: F[Unit] =
    Logger[F].info("IGRINS 2 Sequence complete") *>
      controller.sequenceComplete.handleErrorWith { e =>
        Logger[F].error(e)("Error in sequence complete")
      } *>
      // No obsId parameter here, so we can't clean up

  override def observeControl(config: CleanConfig): InstrumentSystem.ObserveControl[F] =
    InstrumentSystem.UnpausableControl(InstrumentSystem.StopObserveCmd(_ => Async[F].unit),
                                       InstrumentSystem.AbortObserveCmd(abort)
    )

  override def observe(
    config: CleanConfig
  ): Kleisli[F, ImageFileId, ObserveCommandResult] =
    Kleisli { fileId =>
      calcObserveTime(config).flatMap { x =>
        controller
          .observe(fileId, x)
          .as(ObserveCommandResult.Success: ObserveCommandResult)
      }
    }

  override def configure(config: CleanConfig): F[ConfigResult[F]] =
    Igrins2
      .fromSequenceConfig[F](config)
      .flatMap(controller.applyConfig)
      .as(ConfigResult[F](this))

  override def notifyObserveEnd: F[Unit] =
    controller.endObserve

  override def notifyObserveStart: F[Unit] = Sync[F].unit

  override def calcObserveTime(config: CleanConfig): F[Time] =
    MonadError[F, Throwable].catchNonFatal {
      val obsTime =
        for {
          exp <- config.extractObsAs[JDouble](SPIgrins2.EXPOSURE_TIME_PROP)
          t    = Seconds(exp.toDouble)
          f    = SPIgrins2.readoutTime(t)
        } yield t + f + readoutOverhead
      obsTime.getOrElse(readoutOverhead)
    }

  override def observeProgress(
    total:   Time,
    elapsed: InstrumentSystem.ElapsedTime
  ): Stream[F, Progress] =
    Stream.force(
      for {
        progress <- controller.exposureProgress
        totalExp <- controller.requestedTime.map(_.map(Seconds(_)).getOrElse(total))
      } yield ProgressUtil
        .realCountdownWithObsStage[F](
          totalExp,
          progress
            .map(Seconds(_) + Seconds(1.5)),
          (controller.dcIsPreparing,
           controller.dcIsAcquiring,
           controller.dcIsReadingOut,
           controller.dcIsWritingMEF
          ).mapN { (a, b, c, d) =>
            ObserveStage.fromBooleans(a, b, c, d)
          }
        )
    )

  override def instrumentActions(config: CleanConfig): InstrumentActions[F] =
    new InstrumentActions[F] {
      private val defaultActions = InstrumentActions.defaultInstrumentActions[F]
      
      override def observationProgressStream(env: ObserveEnvironment[F]): Stream[F, Result[F]] =
        defaultActions.observationProgressStream(env)
      
      // Custom implementation that uses our cached data labels
      override def observeActions(env: ObserveEnvironment[F]): List[ParallelActions[F]] = {
        val customObserve = Stream.eval(getDataLabel(env.obsId)).flatMap { fileId =>
          Stream.emit(Result.Partial(FileIdAllocated(fileId))) ++
            ObserveActions.stdObserve(fileId, env)
        }.handleErrorWith(ObserveActions.catchObsErrors[F])
        
        InstrumentActions.defaultObserveActions(customObserve)
      }
      
      override def runInitialAction(stepType: StepType): Boolean = true
    }

}

object Igrins2 {
  val INSTRUMENT_NAME_PROP: String = "IGRINS2"

  val name: String = INSTRUMENT_NAME_PROP

  val sfName: String = "IGRINS2"

  def fromSequenceConfig[F[_]: Sync](config: CleanConfig): F[Igrins2Config] =
    EitherT {
      Sync[F].delay {
        (for {
          expTime       <-
            config.extractObsAs[JDouble](SPIgrins2.EXPOSURE_TIME_PROP).map(_.toDouble.seconds)
          clazz         <- config.extractObsAs[String](InstConstants.OBS_CLASS_PROP)
          p              = config.extractTelescopeAs[String](P_OFFSET_PROP)
          q              = config.extractTelescopeAs[String](Q_OFFSET_PROP)
          obsClass       = clazz match {
                             case "acq" | "acqCal" => "acq"
                             case "dayCal"         => "dayCal"
                             case _                => "sci"
                           }
          igrins2Config <-
            Right(new Igrins2Config {
              override def configuration: Configuration =
                Configuration.single("ig2:dcs:expTime", expTime.value) |+|
                  Configuration.single("ig2:seq:state", obsClass) |+|
                  p.foldMap(p => Configuration.single("ig2:seq:p", p.toDouble)) |+|
                  q.foldMap(q => Configuration.single("ig2:seq:q", q.toDouble))
            })
        } yield igrins2Config).leftMap(e => SeqexecFailure.Unexpected(ConfigUtilOps.explain(e)))
      }
    }.widenRethrowT

  object specifics extends InstrumentSpecifics {
    override val instrument: Instrument = Instrument.Igrins2

    override def sfName(config: CleanConfig): LightSinkName = LightSinkName.Igrins2

  }
}
