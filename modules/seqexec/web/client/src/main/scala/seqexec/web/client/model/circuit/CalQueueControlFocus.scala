// Copyright (c) 2016-2018 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package seqexec.web.client.circuit

import cats.Eq
import cats.implicits._
import monocle.Getter
import monocle.Optional
import monocle.macros.Lenses
import monocle.std
import monocle.function.At.at
import monocle.function.At.atMap
import seqexec.model.QueueId
import seqexec.web.client.model._

@Lenses
final case class CalQueueControlFocus(canOperate: Boolean, ops: QueueOperations)

@SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
object CalQueueControlFocus {
  implicit val eq: Eq[CalQueueControlFocus] =
    Eq.by(x => (x.canOperate, x.ops))

  def optQueue(id: QueueId): Optional[SeqexecAppRootModel, QueueOperations] =
    SeqexecAppRootModel.uiModel ^|->
    SeqexecUIModel.queues       ^|->
    CalibrationQueues.ops       ^|->
    at(id)                      ^<-?
    std.option.some

  def queueControlG(id: QueueId): Getter[SeqexecAppRootModel, Option[CalQueueControlFocus]] = {
    ClientStatus.canOperateG.zip(Getter(optQueue(id).getOption)) >>> {
      case (status, Some(c)) =>
        CalQueueControlFocus(status, c).some
      case _ =>
        none
    }
  }
}
