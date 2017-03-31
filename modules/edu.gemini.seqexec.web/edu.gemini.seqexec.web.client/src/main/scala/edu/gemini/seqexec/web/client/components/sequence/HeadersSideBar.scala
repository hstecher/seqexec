package edu.gemini.seqexec.web.client.components.sequence

import diode.react.ModelProxy
import edu.gemini.seqexec.model.Model.{Conditions, ImageQuality}
import edu.gemini.seqexec.web.client.semanticui.elements.dropdown.DropdownMenu
import edu.gemini.seqexec.web.client.semanticui.elements.label.Label
import edu.gemini.seqexec.web.client.semanticui.elements.input.InputEV
import edu.gemini.seqexec.web.client.model._
import edu.gemini.seqexec.web.client.services.SeqexecWebClient
import japgolly.scalajs.react.{BackendScope, Callback, ReactComponentB, ReactComponentU, TopNode}
import japgolly.scalajs.react.extra.{ExternalVar, TimerSupport}
import japgolly.scalajs.react.vdom.prefix_<^._
import org.scalajs.dom.html.Div

import scalaz.syntax.equal._
import scalaz.std.string._
import scalaz.std.option._
import scalaz.syntax.std.option._
import scalaz.Equal

import scala.concurrent.duration._

/**
  * Display to show headers per sequence
  */
object HeadersSideBar {
  case class Props(operator: ModelProxy[Option[String]], conditions: ModelProxy[Conditions], status: ModelProxy[ClientStatus])

  case class State(currentText: Option[String])

  object State {
    implicit val equals: Equal[State] = Equal.equalA[State]
  }

  class Backend(val $: BackendScope[Props, State]) extends TimerSupport {
    def updateState(value: String): Callback =
      $.modState(_.copy(currentText = Some(value)))

    def submitIfChanged: Callback =
      ($.state zip $.props) >>= {
        case (s, p) => Callback.when(s.currentText =/= p.operator())(Callback.empty) // We are not submitting until the backend properly update this property
      }

    def iqChanged(iq: ImageQuality): Callback =
      Callback.log(" IQ " + iq + " " + iq.toString) >> $.props >>= {_.operator.dispatchCB(UpdateImageQuality(iq))}

    def render(p: Props, s: State): ReactTagOf[Div] = {
      val operatorEV = ExternalVar(s.currentText.getOrElse(""))(updateState)
      <.div(
        ^.cls := "ui raised secondary segment",
        <.h4("Headers"),
        <.div(
          ^.cls := "ui form",
          <.div(
            ^.cls := "required field",
            Label(Label.Props("Operator", "operator")),
            InputEV(InputEV.Props("operator", "operator",
              operatorEV,
              placeholder = "Operator...",
              disabled = !p.status().isLogged,
              onBlur = name => Callback.empty// >> p.operator.dispatchCB(UpdateOperator(name)))) TODO Enable when the backend accepts this property
            ))
          ),

          DropdownMenu(DropdownMenu.Props("Image Quality", p.conditions().iq.some, "Select", ImageQuality.all, disabled = !p.status().isLogged, iqChanged)),
          DropdownMenu(DropdownMenu.Props("Cloud Cover", None, "Select", List("CC20", "CC50", "CC70", "CC80", "CC90", "Any"), disabled = !p.status().isLogged)),
          DropdownMenu(DropdownMenu.Props("Water Vapor", None, "Select", List("WV20", "WV50", "WV80", "Any"), disabled = !p.status().isLogged)),
          DropdownMenu(DropdownMenu.Props("Sky Background", None, "Select", List("SB20", "SB50", "SB80", "Any"), disabled = !p.status().isLogged))
        )
      )
    }
  }

  private val component = ReactComponentB[Props]("HeadersSideBar")
    .initialState(State(None))
    .renderBackend[Backend]
    .configure(TimerSupport.install)
    .shouldComponentUpdate { f =>
      // If the state changes, don't update the UI
      f.$.state === f.nextState
    }
    // Every 2 seconds check if the field has changed and submit
    .componentDidMount(c => c.backend.setInterval(c.backend.submitIfChanged, 2.second))
    .build

  def apply(operator: ModelProxy[Option[String]], conditions: ModelProxy[Conditions], status: ModelProxy[ClientStatus]): ReactComponentU[Props, State, Backend, TopNode] =
    component(Props(operator, conditions, status))
}
