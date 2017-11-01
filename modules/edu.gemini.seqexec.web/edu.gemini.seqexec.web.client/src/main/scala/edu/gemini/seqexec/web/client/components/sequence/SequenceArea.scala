// Copyright (c) 2016-2017 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.seqexec.web.client.components.sequence

import diode.ModelR
import diode.react.ModelProxy
import edu.gemini.seqexec.web.client.components.TextMenuSegment
import edu.gemini.seqexec.web.client.components.sequence.toolbars.{SequenceDefaultToolbar, StepConfigToolbar, SequenceAnonymousToolbar}
import edu.gemini.seqexec.web.client.model._
import edu.gemini.seqexec.web.client.circuit.{SeqexecCircuit, StatusAndStepFocus, HeaderSideBarFocus, InstrumentTabContentFocus, ClientStatus}
import edu.gemini.seqexec.web.client.semanticui._
import edu.gemini.seqexec.web.client.semanticui.elements.message.IconMessage
import edu.gemini.seqexec.web.client.semanticui.elements.icon.Icon.IconInbox
import edu.gemini.seqexec.model.Model.SeqexecSite
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CallbackTo, ScalaComponent, ScalazReact}
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.ScalazReact._

import scalaz.syntax.show._

object SequenceStepsTableContainer {
  final case class Props(site: SeqexecSite, p: ModelProxy[StatusAndStepFocus]) {
    private[sequence] val instrumentConnects = site.instruments.list.toList.map(i => (i, SeqexecCircuit.connect(SeqexecCircuit.stepsTableReader(i)))).toMap
  }
  final case class State(nextStepToRun: Int)

  private val ST = ReactS.Fix[State]

  def updateStepToRun(step: Int): ScalazReact.ReactST[CallbackTo, State, Unit] =
    ST.set(State(step)).liftCB

  private val component = ScalaComponent.builder[Props]("SequenceStepsTableContainer")
    .initialState(State(0))
    .renderP { ($, p) =>
        <.div(
          ^.cls := "ui raised secondary segment",
          p.p().stepConfigDisplayed.fold{
            if (p.p().isLogged)
              SequenceDefaultToolbar(p.site, p.p().instrument): VdomElement
            else
              SequenceAnonymousToolbar(p.site, p.p().instrument): VdomElement
          }(s => StepConfigToolbar(StepConfigToolbar.Props(p.site, p.p().instrument, p.p().isLogged, s))),
          <.div(
            ^.cls := "ui raised secondary segment",
            p.instrumentConnects.get(p.p().instrument).whenDefined(x => x(m => StepsTableContainer(StepsTableContainer.Props(m, x => $.runState(updateStepToRun(x))))))
          )
        )
    }.build

  def apply(site: SeqexecSite, p: ModelProxy[StatusAndStepFocus]): Unmounted[Props, State, Unit] = component(Props(site, p))
}

/**
* Content of a single tab with a sequence
*/
object SequenceTabContent {

  final case class Props(site: SeqexecSite, p: ModelProxy[InstrumentTabContentFocus]) {
    protected[sequence] val connect = SeqexecCircuit.connect(SeqexecCircuit.statusAndStepReader(p().instrument))
  }

  private val component = ScalaComponent.builder[Props]("SequenceTabContent")
    .stateless
    .render_P { p =>
      val InstrumentTabContentFocus(instrument, active, sequenceSelected) = p.p()
      <.div(
        ^.cls := "ui attached tab segment",
        ^.classSet(
          "active" -> active
        ),
        dataTab := instrument.shows,
        IconMessage(IconMessage.Props(IconInbox, Some("No sequence loaded"), IconMessage.Style.Warning)).unless(sequenceSelected),
        p.connect(st => SequenceStepsTableContainer(p.site, st)).when(sequenceSelected)
      )
    }
    .build

    def apply(site: SeqexecSite, p: ModelProxy[InstrumentTabContentFocus]): Unmounted[Props, Unit, Unit] = component(Props(site, p))
}

/**
 * Contains the area with tabs and the sequence body
 */
object SequenceTabsBody {
  final case class Props(site: SeqexecSite) {
    protected[sequence] val instrumentConnects = site.instruments.list.map(i => SeqexecCircuit.connect(SeqexecCircuit.instrumentTabContentReader(i)))
  }

  private val component = ScalaComponent.builder[Props]("SequenceTabsBody")
    .stateless
    .render_P(p =>
      <.div(
        ^.cls := "ui grid",
        <.div(
          ^.cls := "stretched row",
          <.div(
            ^.cls := "sixteen wide column",
            <.div(
              InstrumentsTabs(p.site),
              p.instrumentConnects.map(c => c(s => SequenceTabContent(p.site, s))).toList.toTagMod
            )
          )
        )
      )
    ).build

  def apply(site: SeqexecSite): Unmounted[Props, Unit, Unit] = component(Props(site))
}

/**
  * Component containing the sidebar on the left and the sequence tabs on the right
  */
object SequenceHeadersAndTable {
  private val headerSideBarConnect = SeqexecCircuit.connect(SeqexecCircuit.headerSideBarReader)

  private val component = ScalaComponent.builder[SeqexecSite]("SequenceHeadersAndTable")
    .stateless
    .render_P(p =>
      <.div(<.div(
        ^.cls := "row",
        SequenceTabsBody(p)
      ),
      <.div(
        ^.cls := "row",
        <.div(
          ^.cls := "sixteen wide column computer tablet only",
          headerSideBarConnect(HeadersSideBar.apply)
        )
      ))
    )
    .build

  def apply(site: SeqexecSite): Unmounted[SeqexecSite, Unit, Unit] = component(site)
}

/**
 * Contains all the tabs for the sequences available in parallel
 * All connects at this level, be careful about adding connects below here
 */
object SequenceTabs {
  private val component = ScalaComponent.builder[SeqexecSite]("SequenceTabs")
    .stateless
    .render_P( p =>
      <.div(
        ^.cls := "ui bottom attached segment",
        <.div(
          ^.cls := "ui grid",
          SequenceHeadersAndTable(p)
        )
      )
    )
    .build

  def apply(site: SeqexecSite): Unmounted[SeqexecSite, Unit, Unit] = component(site)
}

/**
 * Top level container of the sequence area
 */
object SequenceArea {
  type SequencesModel = ModelR[SeqexecAppRootModel, (ClientStatus, SequencesOnDisplay)]
  type HeadersSideBarModel = ModelR[SeqexecAppRootModel, HeaderSideBarFocus]

  private val component = ScalaComponent.builder[SeqexecSite]("QueueTableSection")
    .stateless
    .render_P( p =>
      <.div(
        ^.cls := "ui raised segments container",
        TextMenuSegment("Running Sequences", "key.sequences.menu"),
        <.div(
          ^.cls := "ui bottom attached segment",
          SequenceTabsBody(p)
        )
      )
    ).build

  def apply(site: SeqexecSite): Unmounted[SeqexecSite, Unit, Unit] = component(site)
}
