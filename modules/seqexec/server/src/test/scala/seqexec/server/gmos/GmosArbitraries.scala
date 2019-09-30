// Copyright (c) 2016-2019 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package seqexec.server.gmos

import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary._
import org.scalacheck.Cogen
import shapeless.tag
import seqexec.server.gmos.GmosController._

trait GmosArbitraries {
  implicit val gmosNsPairsArb: Arbitrary[NsPairs] =
    Arbitrary(arbitrary[Int].map(tag[NsPairsI][Int]))
  implicit val gmosNsPairsCogen: Cogen[NsPairs] =
    Cogen[Int].contramap(identity)
  implicit val gmosNsRowsArb: Arbitrary[NsRows] =
    Arbitrary(arbitrary[Int].map(tag[NsRowsI][Int]))
  implicit val gmosNsRowsCogen: Cogen[NsRows] =
    Cogen[Int].contramap(identity)
}