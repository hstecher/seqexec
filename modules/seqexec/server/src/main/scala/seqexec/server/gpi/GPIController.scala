// Copyright (c) 2016-2018 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package seqexec.server.gpi

import cats.{Eq, Show}
import cats.data.EitherT
import cats.effect.IO
import cats.implicits._
import edu.gemini.aspen.giapi.commands.{Configuration, DefaultConfiguration}
import edu.gemini.aspen.giapi.commands.ConfigPath.configPath
import edu.gemini.spModel.gemini.gpi.Gpi.{Apodizer => LegacyApodizer}
import edu.gemini.spModel.gemini.gpi.Gpi.{Adc => LegacyAdc}
import edu.gemini.spModel.gemini.gpi.Gpi.{
  ArtificialSource => LegacyArtificialSource
}
import edu.gemini.spModel.gemini.gpi.Gpi.{Disperser => LegacyDisperser}
import edu.gemini.spModel.gemini.gpi.Gpi.{FPM => LegacyFPM}
import edu.gemini.spModel.gemini.gpi.Gpi.{Lyot => LegacyLyot}
import edu.gemini.spModel.gemini.gpi.Gpi.{ObservingMode => LegacyObservingMode}
import edu.gemini.spModel.gemini.gpi.Gpi.{PupilCamera => LegacyPupilCamera}
import edu.gemini.spModel.gemini.gpi.Gpi.{Shutter => LegacyShutter}
import giapi.client.commands.CommandResult
import giapi.client.gpi.GPIClient
import mouse.boolean._
import org.log4s.getLogger
import scala.concurrent.duration.Duration
import seqexec.model.dhs.ImageFileId
import seqexec.server.SeqAction

object GPILookupTables {
  val apodizerLUT: Map[LegacyApodizer, String] = Map(
    LegacyApodizer.CLEAR     -> "CLEAR",
    LegacyApodizer.CLEARGP   -> "CLEARGP",
    LegacyApodizer.APOD_Y    -> "APOD_Y",
    LegacyApodizer.APOD_J    -> "APOD_J",
    LegacyApodizer.APOD_H    -> "APOD_H",
    LegacyApodizer.APOD_K1   -> "APOD_K1",
    LegacyApodizer.APOD_K2   -> "APOD_K2",
    LegacyApodizer.NRM       -> "NRM",
    LegacyApodizer.APOD_HL   -> "APOD_HL",
    LegacyApodizer.APOD_STAR -> "ND3",
    LegacyApodizer.ND3       -> "ND3"
  )

  val fpmLUT: Map[LegacyFPM, String] = Map(
    LegacyFPM.OPEN     -> "Open",
    LegacyFPM.F50umPIN -> "50umPIN",
    LegacyFPM.WITH_DOT -> "WITH_DOT",
    LegacyFPM.FPM_Y    -> "FPM_Y",
    LegacyFPM.FPM_J    -> "FPM_J",
    LegacyFPM.FPM_H    -> "FPM_H",
    LegacyFPM.FPM_K1   -> "FPM_K1",
    LegacyFPM.SCIENCE  -> "SCIENCE"
  )

  val lyotLUT: Map[LegacyLyot, String] = Map(
    LegacyLyot.OPEN              -> "Open",
    LegacyLyot.BLANK             -> "Blank",
    LegacyLyot.LYOT_080m12_03    -> "080m12_03",
    LegacyLyot.LYOT_080m12_04    -> "080m12_04",
    LegacyLyot.LYOT_080_04       -> "080_04",
    LegacyLyot.LYOT_080m12_06    -> "080m12_06",
    LegacyLyot.LYOT_080m12_04_c  -> "080m12_04_c",
    LegacyLyot.LYOT_080m12_06_03 -> "080m12_06_03",
    LegacyLyot.LYOT_080m12_07    -> "080m12_07",
    LegacyLyot.LYOT_080m12_10    -> "080m12_10"
  )

  val obsModeLUT: Map[LegacyObservingMode, String] = Map(
    LegacyObservingMode.CORON_Y_BAND   -> "Y_coron",
    LegacyObservingMode.CORON_J_BAND   -> "J_coron",
    LegacyObservingMode.CORON_H_BAND   -> "H_coron",
    LegacyObservingMode.CORON_K1_BAND  -> "K1_coron",
    LegacyObservingMode.CORON_K2_BAND  -> "K2_coron",
    LegacyObservingMode.H_STAR         -> "H_starcor",
    LegacyObservingMode.H_LIWA         -> "H_LIWAcor",
    LegacyObservingMode.DIRECT_Y_BAND  -> "Y_direct",
    LegacyObservingMode.DIRECT_J_BAND  -> "J_direct",
    LegacyObservingMode.DIRECT_H_BAND  -> "H_direct",
    LegacyObservingMode.DIRECT_K1_BAND -> "K1_direct",
    LegacyObservingMode.DIRECT_K2_BAND -> "K2_direct",
    LegacyObservingMode.NRM_Y          -> "NRM_Y",
    LegacyObservingMode.NRM_J          -> "NRM_J",
    LegacyObservingMode.NRM_H          -> "NRM_H",
    LegacyObservingMode.NRM_K1         -> "NRM_K1",
    LegacyObservingMode.NRM_K2         -> "NRM_K2",
    LegacyObservingMode.DARK           -> "DARK",
    LegacyObservingMode.UNBLOCKED_Y    -> "Y_unblocked",
    LegacyObservingMode.UNBLOCKED_J    -> "J_unblocked",
    LegacyObservingMode.UNBLOCKED_H    -> "H_unblocked",
    LegacyObservingMode.UNBLOCKED_K1   -> "K1_unblocked",
    LegacyObservingMode.UNBLOCKED_K2   -> "K2_unblocked"
  )
}

final case class GPIController(gpiClient: GPIClient[IO]) {
  import GPIController._
  import GPILookupTables._
  private val Log = getLogger
  private val UNKNOWN_SETTING = "UNKNOWN"

  private def obsModeConfiguration(config: GPIConfig): Configuration = {
    config.mode.fold( m =>
      DefaultConfiguration.configuration(configPath("gpi:observationMode.mode"), obsModeLUT.getOrElse(m, UNKNOWN_SETTING))
    , params => {
      DefaultConfiguration.configurationBuilder()
        .withConfiguration("gpi:selectPupilPlaneMask.maskStr", apodizerLUT.getOrElse(params.apodizer, UNKNOWN_SETTING))
        .withConfiguration("gpi:selectFocalPlaneMask.maskStr", fpmLUT.getOrElse(params.fpm, UNKNOWN_SETTING))
        .withConfiguration("gpi:selectLyotMask.maskStr", lyotLUT.getOrElse(params.lyot, UNKNOWN_SETTING))
        .build()
    })
  }

  // scalastyle:off
  def gpiConfig(config: GPIConfig): SeqAction[CommandResult] = {
    val giapiApply = DefaultConfiguration
      .configurationBuilder()
      .withConfiguration("gpi:selectAdc.deploy",
                         (config.adc === LegacyAdc.IN)
                           .fold(1, 0)
                           .show)
      .withConfiguration("gpi:configAo.useAo",
                         config.aoFlags.useAo
                           .fold(1, 0)
                           .show)
      .withConfiguration("gpi:configAo.useCal",
                         config.aoFlags.useCal
                           .fold(1, 0)
                           .show)
      .withConfiguration("gpi:configCal.fpmPinholeBias",
                         (config.aoFlags.alignFpm)
                           .fold(1, 0)
                           .show)
      .withConfiguration("gpi:configAo.optimize",
                         config.aoFlags.aoOptimize
                           .fold(1, 0)
                           .show)
      .withConfiguration("gpi:configIfs.integrationTime",
                         (config.expTime.toMillis / 1000.0).show)
      .withConfiguration("gpi:configIfs.numCoadds", config.coAdds.show)
      .withConfiguration("gpi:configAo.magnitudeI", config.aoFlags.magI.show)
      .withConfiguration("gpi:configAo.magnitudeH", config.aoFlags.magH.show)
      .withConfiguration(
        "gpi:selectShutter.calEntranceShutter",
        (config.shutters.calEntranceShutter === LegacyShutter.OPEN)
          .fold(1, 0)
          .show)
      .withConfiguration(
        "gpi:selectShutter.calReferenceShutter",
        (config.shutters.calReferenceShutter === LegacyShutter.OPEN)
          .fold(1, 0)
          .show)
      .withConfiguration(
        "gpi:selectShutter.calScienceShutter",
        (config.shutters.calScienceShutter === LegacyShutter.OPEN)
          .fold(1, 0)
          .show)
      .withConfiguration(
        "gpi:selectShutter.entranceShutter",
        (config.shutters.entranceShutter === LegacyShutter.OPEN)
          .fold(1, 0)
          .show)
      .withConfiguration("gpi:selectShutter.calExitShutter", "-1")
      .withConfiguration(obsModeConfiguration(config))
      .withConfiguration("gpi:selectPupilCamera.deploy",
                         (config.pc === LegacyPupilCamera.IN)
                           .fold(1, 0)
                           .show)
      .withConfiguration("gpi:selectSource.sourceSCatten",
                         config.asu.attenuation.show)
      .withConfiguration("gpi:selectSource.sourceSCpower",
                         (config.asu.sc === LegacyArtificialSource.ON)
                           .fold(100, 0)
                           .show)
      .withConfiguration("gpi:selectSource.sourceVis",
                         (config.asu.vis === LegacyArtificialSource.ON)
                           .fold(1, 0)
                           .show)
      .withConfiguration("gpi:selectSource.sourceIr",
                         (config.asu.ir === LegacyArtificialSource.ON)
                           .fold(1, 0)
                           .show)
      .withConfiguration("gpi:selectSource.deploy",
                         (config.disperser === LegacyDisperser.WOLLASTON)
                           .fold(1, 0)
                           .show)
      .withConfiguration("gpi:configPolarizer.angle",
                         config.disperserAngle.show)

    EitherT.liftF(gpiClient.genericApply(giapiApply.build()))
  }
  // scalastyle:on

  def applyConfig(config: GPIConfig): SeqAction[Unit] =
    for {
      _ <- EitherT.liftF(IO.apply(Log.debug("Start GPI configuration")))
      _ <- EitherT.liftF(IO.apply(Log.debug(s"GPI configuration $config")))
      _ <- gpiConfig(config)
      _ <- EitherT.liftF(IO(Log.debug("Completed GPI configuration")))
    } yield ()

  def observe(fileId: ImageFileId): SeqAction[ImageFileId] =
    EitherT(gpiClient.observe(fileId).map(_ => fileId.asRight))

  def endObserve: SeqAction[Unit] =
    SeqAction.void
}

object GPIController {

  implicit val apodizerEq: Eq[LegacyApodizer] = Eq.by(_.displayValue)

  implicit val adcEq: Eq[LegacyAdc] = Eq.by(_.displayValue)

  implicit val omEq: Eq[LegacyObservingMode] = Eq.by(_.displayValue)

  implicit val dispEq: Eq[LegacyDisperser] = Eq.by(_.displayValue)

  implicit val fpmEq: Eq[LegacyFPM] = Eq.by(_.displayValue)

  implicit val lyotEq: Eq[LegacyLyot] = Eq.by(_.displayValue)

  implicit val shEq: Eq[LegacyShutter] = Eq.by(_.displayValue)

  implicit val asEq: Eq[LegacyArtificialSource] = Eq.by(_.displayValue)

  implicit val pcEq: Eq[LegacyPupilCamera] = Eq.by(_.displayValue)

  final case class AOFlags(useAo: Boolean,
                           useCal: Boolean,
                           aoOptimize: Boolean,
                           alignFpm: Boolean,
                           magH: Double,
                           magI: Double)

  object AOFlags {
    implicit val eq: Eq[AOFlags]     = Eq.fromUniversalEquals
    implicit val show: Show[AOFlags] = Show.fromToString
  }

  final case class ArtificialSources(ir: LegacyArtificialSource,
                                     vis: LegacyArtificialSource,
                                     sc: LegacyArtificialSource,
                                     attenuation: Double)

  object ArtificialSources {
    implicit val eq: Eq[ArtificialSources] =
      Eq.by(x => (x.ir, x.vis, x.sc, x.attenuation))
    implicit val show: Show[ArtificialSources] = Show.fromToString
  }

  final case class Shutters(entranceShutter: LegacyShutter,
                            calEntranceShutter: LegacyShutter,
                            calScienceShutter: LegacyShutter,
                            calReferenceShutter: LegacyShutter)

  object Shutters {
    implicit val eq: Eq[Shutters] = Eq.by(
      x =>
        (x.entranceShutter,
         x.calEntranceShutter,
         x.calScienceShutter,
         x.calReferenceShutter))
    implicit val show: Show[Shutters] = Show.fromToString
  }

  final case class NonStandardModeParams(apodizer: LegacyApodizer, fpm: LegacyFPM, lyot: LegacyLyot)

  object NonStandardModeParams {
    implicit val eq: Eq[NonStandardModeParams] = Eq.by(
      x =>
        (x.apodizer, x.fpm, x.lyot))
    implicit val show: Show[NonStandardModeParams] = Show.fromToString
  }

  final case class GPIConfig(adc: LegacyAdc,
                             expTime: Duration,
                             coAdds: Int,
                             mode: Either[LegacyObservingMode, NonStandardModeParams],
                             disperser: LegacyDisperser,
                             disperserAngle: Double,
                             shutters: Shutters,
                             asu: ArtificialSources,
                             pc: LegacyPupilCamera,
                             aoFlags: AOFlags)

  object GPIConfig {
    private implicit val durationEq: Eq[Duration] = Eq.by(_.toMillis)
    implicit val eq: Eq[GPIConfig] = Eq.by(
      x =>
        (x.adc,
         x.expTime,
         x.coAdds,
         x.mode,
         x.disperser,
         x.disperserAngle,
         x.shutters,
         x.asu,
         x.pc,
         x.aoFlags))
    implicit val show: Show[GPIConfig] = Show.fromToString
  }
}
