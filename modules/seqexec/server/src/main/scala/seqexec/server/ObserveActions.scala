// Copyright (c) 2016-2019 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package seqexec.server

import cats._
import cats.implicits._
import fs2.Stream
import gem.Observation
import io.chrisdavenport.log4cats.Logger
import seqexec.engine._
import seqexec.model.dhs._
import seqexec.model.enum.ObserveCommandResult
import seqexec.server.InstrumentSystem.ElapsedTime
import squants.time.TimeConversions._
import SeqTranslate.dataIdFromConfig

/**
  * Methods usedd to generate observation related actions
  */
trait ObserveActions {

  private def info[F[_]: Logger](msg: => String): F[Unit] = Logger[F].info(msg)

  /**
    * Actions to perform when an observe is aborted
    */
  def abortTail[F[_]: MonadError[?[_], Throwable]](
    systems:     Systems[F],
    obsId:       Observation.Id,
    imageFileId: ImageFileId
  ): F[Result[F]] =
    systems.odb
      .obsAbort(obsId, imageFileId)
      .ensure(
        SeqexecFailure
          .Unexpected("Unable to send ObservationAborted message to ODB.")
      )(identity) *>
      MonadError[F, Throwable].raiseError(SeqexecFailure.Aborted(obsId))

  /**
    * Send the datasetStart command to the odb
    */
  private def sendDataStart[F[_]: MonadError[?[_], Throwable]](
    systems:     Systems[F],
    obsId:       Observation.Id,
    imageFileId: ImageFileId,
    dataId:      DataId
  ): F[Unit] =
    systems.odb
      .datasetStart(obsId, dataId, imageFileId)
      .ensure(
        SeqexecFailure.Unexpected("Unable to send DataStart message to ODB.")
      )(identity)
      .void

  /**
    * Send the datasetEnd command to the odb
    */
  private def sendDataEnd[F[_]: MonadError[?[_], Throwable]](
    systems:     Systems[F],
    obsId:       Observation.Id,
    imageFileId: ImageFileId,
    dataId:      DataId
  ): F[Unit] =
    systems.odb
      .datasetComplete(obsId, dataId, imageFileId)
      .ensure(
        SeqexecFailure.Unexpected("Unable to send DataEnd message to ODB.")
      )(identity)
      .void

  /**
    * Standard progress stream for an observation
    */
  def observationProgressStream[F[_]](
    env: ObserveEnvironment[F]
  ): Stream[F, Result[F]] =
    for {
      ot <- Stream.eval(env.inst.calcObserveTime(env.config))
      pr <- env.inst.observeProgress(ot, ElapsedTime(0.0.seconds))
    } yield Result.Partial(pr)

  /**
    * Tell each subsystem that an observe will start
    */
  def notifyObserveStart[F[_]: Applicative](
    env: ObserveEnvironment[F]
  ): F[Unit] =
    env.otherSys.traverse_(_.notifyObserveStart)

  /**
    * Tell each subsystem that an observe will end
    * Unlike observe start we also tell the instrumetn about it
    */
  def notifyObserveEnd[F[_]: Applicative](env: ObserveEnvironment[F]): F[Unit] =
    (env.inst +: env.otherSys).traverse_(_.notifyObserveEnd)

  /**
    * Close the image, telling either DHS or GDS as it correspond
    */
  def closeImage[F[_]](id: ImageFileId, env: ObserveEnvironment[F]): F[Unit] =
    env.inst.keywordsClient.closeImage(id)

  /**
    * Read the data id value from the sequence
    */
  def dataId[F[_]: MonadError[?[_], Throwable]](
    env: ObserveEnvironment[F]
  ): F[DataId] =
    dataIdFromConfig[F](env.config)

  /**
    * Preamble for observations. It tells the odb, the subsystems
    * send the start headers and finally sends an observe
    */
  def observePreamble[F[_]: MonadError[?[_], Throwable]: Logger](
    fileId: ImageFileId,
    env:    ObserveEnvironment[F]
  ): F[(DataId, ObserveCommandResult)] =
    for {
      d <- dataId(env)
      _ <- sendDataStart(env.systems, env.obsId, fileId, d)
      _ <- notifyObserveStart(env)
      _ <- env.headers(env.ctx).traverse(_.sendBefore(env.obsId, fileId))
      _ <- info(s"Start ${env.inst.resource.show} observation ${env.obsId.format} with label $fileId")
      r <- env.inst.observe(env.config)(fileId)
      _ <- info(s"Completed ${env.inst.resource.show} observation ${env.obsId.format} with label $fileId")
    } yield (d, r)

  /**
    * End of an observation for a typical instrument
    * It tells the odb and each subsystem and also sends the end
    * observation keywords
    */
  def okTail[F[_]: MonadError[?[_], Throwable]](
    fileId:  ImageFileId,
    dataId:  DataId,
    stopped: Boolean,
    env:     ObserveEnvironment[F]
  ): F[Result[F]] =
    for {
      _ <- notifyObserveEnd(env)
      _ <- env.headers(env.ctx).reverseMap(_.sendAfter(fileId)).sequence.void
      _ <- closeImage(fileId, env)
      _ <- sendDataEnd[F](env.systems, env.obsId, fileId, dataId)
    } yield
      if (stopped) Result.OKStopped(Response.Observed(fileId))
      else Result.OK(Response.Observed(fileId))

  /**
    * Method to process observe results and act accordingly to the response
    */
  private def observeTail[F[_]: MonadError[?[_], Throwable]](
    fileId: ImageFileId,
    dataId: DataId,
    env:    ObserveEnvironment[F]
  )(r:      ObserveCommandResult): Stream[F, Result[F]] = {
    Stream.eval(r match {
      case ObserveCommandResult.Success =>
        okTail(fileId, dataId, stopped = false, env)
      case ObserveCommandResult.Stopped =>
        okTail(fileId, dataId, stopped = true, env)
      case ObserveCommandResult.Aborted =>
        abortTail(env.systems, env.obsId, fileId)
      case ObserveCommandResult.Paused =>
        env.inst
          .calcObserveTime(env.config)
          .map(e => Result.Paused(ObserveContext(observeTail(fileId, dataId, env), e)))
      case ObserveCommandResult.Partial =>
        // This shouldn't happen in normal observations. Raise an error
        MonadError[F, Throwable]
          .raiseError(SeqexecFailure.Execution("Unuspported Partial observation"))
    })
  }

  /**
    * Observe for a typical instrument
    */
  def stdObserve[F[_]: MonadError[?[_], Throwable]: Logger](
    fileId: ImageFileId,
    env:    ObserveEnvironment[F]
  ): Stream[F, Result[F]] =
    for {
      (dataId, result) <- Stream.eval(observePreamble(fileId, env))
      ret              <- observeTail(fileId, dataId, env)(result)
    } yield ret

}

object ObserveActions extends ObserveActions