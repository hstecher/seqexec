// Copyright (c) 2016-2017 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package gem.dao.meta

import doobie._
import gem.Program

trait ProgramIdMeta {
  import PrismMeta._

  // Program.Id as standard formatted string.
  implicit val ProgramIdMeta: Meta[Program.Id] =
    Program.Id.Optics.fromString.asMeta

}
object ProgramIdMeta extends ProgramIdMeta
