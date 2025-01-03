package io.joern.x2cpg.frontendspecific.pysrc2cpg

import io.joern.x2cpg.passes.frontend.XInheritanceFullNamePass
import io.shiftleft.codepropertygraph.generated.Cpg

/** Using some basic heuristics, will try to resolve type full names from types found within the CPG. Requires
  * ImportPass as a pre-requisite.
  */
class PythonInheritanceNamePass(cpg: Cpg) extends XInheritanceFullNamePass(cpg) {

  override val moduleName: String = Constants.moduleName
  override val fileExt: String    = ".py"

}
