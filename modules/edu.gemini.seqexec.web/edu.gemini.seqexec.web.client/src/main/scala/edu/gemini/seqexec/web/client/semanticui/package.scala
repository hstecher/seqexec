// Copyright (c) 2016-2017 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.seqexec.web.client

import japgolly.scalajs.react.vdom.html_<^.VdomAttr

package object semanticui extends SemanticUISize with SemanticUIWidth with SemanticUIAlign {
  // Custom attributes used by SemanticUI
  val dataTab     = VdomAttr("data-tab")
  val dataTooltip = VdomAttr("data-tooltip")
  val formId      = VdomAttr("form")

}