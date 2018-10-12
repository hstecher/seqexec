// Copyright (c) 2016-2018 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package seqexec.web.client.circuit

import cats.Eq
import cats.implicits._
import monocle.Getter
import monocle.Optional
import monocle.Traversal
import monocle.macros.Lenses
import monocle.std
import monocle.function.At.at
import seqexec.model.ExecutionQueueView
import seqexec.model.QueueId
import seqexec.model.enum.BatchCommandState
import seqexec.model.enum.BatchExecState
import seqexec.web.client.model._

@Lenses
final case class CalQueueControlFocus(canOperate: Boolean,
                                      state:      BatchCommandState,
                                      execState:  BatchExecState,
                                      ops:        QueueOperations)

@SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
object CalQueueControlFocus {
  implicit val eq: Eq[CalQueueControlFocus] =
    Eq.by(x => (x.canOperate, x.state, x.execState, x.ops))

  def optQueue(id: QueueId): Optional[SeqexecAppRootModel, QueueOperations] =
    SeqexecAppRootModel.uiModel ^|->
      SeqexecUIModel.queues     ^|->
      CalibrationQueues.queues  ^|->
      at(id)                    ^<-?
      std.option.some           ^|->
      CalQueueState.ops

  def cmdStateT(id: QueueId): Traversal[SeqexecAppRootModel, BatchCommandState] =
    SeqexecAppRootModel.executionQueuesT(id) ^|->
      ExecutionQueueView.cmdState

  def execStateT(id: QueueId): Traversal[SeqexecAppRootModel, BatchExecState] =
    SeqexecAppRootModel.executionQueuesT(id) ^|->
      ExecutionQueueView.execState

  def queueControlG(id: QueueId): Getter[SeqexecAppRootModel, Option[CalQueueControlFocus]] =
    ClientStatus.canOperateG.zip(
      Getter(optQueue(id).getOption)
        .zip(Getter(cmdStateT(id).headOption)
          .zip(Getter(execStateT(id).headOption)))) >>> {
      case (status, (Some(c), (Some(s), Some(e)))) =>
        CalQueueControlFocus(status, s, e, c).some
      case _ =>
        none
    }
}