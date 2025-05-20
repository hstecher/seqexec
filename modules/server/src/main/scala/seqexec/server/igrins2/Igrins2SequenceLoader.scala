// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package seqexec.server.igrins2

import cats.effect.Sync
import cats.syntax.all._
import org.typelevel.log4cats.Logger
import seqexec.model.Observation
import seqexec.model.enum.Instrument
import seqexec.server.SequenceGen
import seqexec.server.StepType
import seqexec.server.keywords.DhsClientProvider

/**
 * Helper class to pre-request data labels for IGRINS sequences
 */
object Igrins2SequenceLoader {

  /**
   * Counts the number of steps in a sequence that require data labels
   * (essentially steps that perform observations)
   */
  def countObserveSteps[F[_]: Sync](sequence: SequenceGen[F]): Int =
    sequence.steps.count { step =>
      // Any step that is pending and not a calibration usually needs a data label
      step match {
        case s: SequenceGen.PendingStepGen[F] => 
          s.generator.post(s.config, Map.empty).nonEmpty
        case _ => false
      }
    }

  /**
   * Automatically pre-request all data labels needed for an IGRINS sequence
   */
  def preRequestLabels[F[_]: Sync: Logger](
    obsId: Observation.Id,
    sequence: SequenceGen[F],
    igrins2: Igrins2[F]
  ): F[Unit] = {
    if (sequence.instrument === Instrument.Igrins2) {
      val steps = countObserveSteps(sequence)
      Logger[F].info(s"Pre-requesting $steps data labels for IGRINS sequence ${obsId.format}") *>
        igrins2.preRequestDataLabels(obsId, steps)
    } else {
      Sync[F].unit // Not an IGRINS sequence, do nothing
    }
  }
  
  /**
   * Clean up any unused data labels for a sequence
   */
  def cleanupLabels[F[_]: Sync: Logger](obsId: Observation.Id): F[Unit] =
    Logger[F].debug(s"Cleaning up IGRINS data labels for sequence ${obsId.format}") *>
      Sync[F].delay(Igrins2DataLabelCache.clear(obsId))
} 