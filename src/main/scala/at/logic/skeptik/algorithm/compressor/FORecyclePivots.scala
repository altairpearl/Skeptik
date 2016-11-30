package at.logic.skeptik.algorithm.compressor

import at.logic.skeptik.proof.sequent._
import at.logic.skeptik.proof.Proof
import at.logic.skeptik.judgment.immutable.SetSequent
import collection.mutable.{ HashMap => MMap, HashSet => MSet }

abstract class FORecyclePivots
  extends FOAbstractRPIAlgorithm with FOCollectEdgesUsingSafeLiterals with FOUnitsCollectingBeforeFixing {

  def apply(proof: Proof[SequentProofNode]) = {
    val unifiableVars = getAllVars(proof);
    val firstPassResults = collectEdgesToDelete(proof)
    val edgesToDelete = firstPassResults._1
    val auxMap = firstPassResults._2
    val mguMap = firstPassResults._3
    val dMap = new MSet[String]()
    if (edgesToDelete.isEmpty) {
      proof
    } else {
      println(edgesToDelete.edges.size + " edges to delete")
      Proof(proof.foldDown(fixProofNodes(edgesToDelete, unifiableVars, auxMap, mguMap, dMap)))
    }

  }

}

// Intersection trait is defined in FORPILU.scala

trait FOoutIntersection
  extends FOAbstractRPIAlgorithm {

  protected def computeSafeLiterals(node: SequentProofNode,
    childrensSafeLiterals: Seq[(SequentProofNode, SetSequent)],
    edgesToDelete: FOEdgesToDelete): SetSequent =
    if (childrensSafeLiterals.length == 1)
      safeLiteralsFromChild(childrensSafeLiterals.head, node, edgesToDelete)
    else
      SetSequent()

}

trait FOconclusionSequent
  extends FOAbstractRPIAlgorithm {

  protected def computeSafeLiterals(node: SequentProofNode,
    childrensSafeLiterals: Seq[(SequentProofNode, SetSequent)],
    edgesToDelete: FOEdgesToDelete): SetSequent =
    if (childrensSafeLiterals.length == 1) {
      safeLiteralsFromChild(childrensSafeLiterals.head, node, edgesToDelete)
    } else {
      node.conclusion.toSetSequent
    }

}

object FORecyclePivots
  extends FORecyclePivots with FOoutIntersection

object FORecyclePivotsWithIntersection
  extends FORecyclePivots with FOIntersection

object FORecyclePivotsWithConclusionSequent
  extends FORecyclePivots with FOconclusionSequent
