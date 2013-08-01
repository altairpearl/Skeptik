package at.logic.skeptik

import at.logic.skeptik.parser.{ProofParser,ProofParserVeriT,ProofParserSkeptik,AlgorithmParser}
import at.logic.skeptik.exporter.{ProofExporterVeriT,ProofExporterSkeptik}
import at.logic.skeptik.judgment.Judgment
import at.logic.skeptik.proof.Proof
import at.logic.skeptik.proof.sequent.{SequentProofNode => N}
import at.logic.skeptik.proof.measure
import at.logic.skeptik.util.time._
import at.logic.skeptik.util.io.{Input,Output,NoOutput,StandardOutput,FileOutput}

import collection.mutable.{HashMap=>MMap}


object ProofCompressionCLI {

  case class Config(inputs: Seq[String] = Seq(),
                    directory: String = "",
                    algorithms: Seq[String] = Seq(), 
                    format: String = "",
                    hout: Output = StandardOutput, // human-readable output
                    mout: Output = NoOutput, // machine-readable output
                    moutHeader: Boolean = true)                

    
  val supportedProofFormats = Seq("smt2", "skeptik")
  
  def unknownFormat(filename: String) = "Unknown proof format for " + filename + ". Supported formats are '.smt2' and '.skeptik'"                 
  
  def completedIn(t: Double) = " (completed in " + Math.round(t) + "ms)"       
  
  def unknownAlgorithm(a: String) = "Algorithm " + a + " is unknown."

  val parser = new scopt.OptionParser[Config]("compress"){
    
    head("\nSkeptik's Command Line Interface for Proof Compression\n\n") 

    opt[String]('a', "algorithm") unbounded() action { (v, c) => 
      c.copy(algorithms = c.algorithms ++ Seq(v))
    } text("use <alg> to compress proofs") valueName("<alg>")
    
    note(
    """
      <alg> can be any of the following atomic algorithms:
    """ + 
    (for (a <- at.logic.skeptik.algorithm.compressor.algorithms) yield a._1).mkString(",") + "\n" + 
    """
      or a sequential composition denoted by '(alg1*alg2*...*algN)'
    """    
    )  
    
    opt[String]("algorithms") action { (v, c) => 
      c.copy(algorithms = c.algorithms ++ Input(c.directory + v).lines) 
    } text("use algorithms listed in <file>\n") valueName("<file>")
    
    opt[String]('d', "directory") unbounded() action { (v, c) => 
      c.copy(directory = v)
    } text("set working directory to <dir>") valueName("<dir>")
    
    opt[String]('o', "output") action { (v, c) => 
      c.copy(format = v) 
    } validate { v =>
      if (supportedProofFormats contains v) success 
      else failure("unknown proof format: " + v)
    } text("use <format> to output compressed proofs") valueName("<format>")
 
      note(
      """
      <format> can be:
        smt2    - VeriT's proof format
        skeptik - Skeptik's proof format
    """
    )

    opt[String]('m', "mout") action { (v, c) =>
      c.copy(mout = Output(c.directory + v)) 
    } text("output measurements to <file>\n") valueName("<file>")
    
    opt[Unit]("disable-header") action { (_, c) =>
      c.copy(moutHeader = false) 
    } text("disable headers in csv output files\n")
    
    opt[String]('h', "hout") action { (v, c) =>
      c.copy(hout = Output(c.directory + v)) 
    } text("output human readable measurements to <file>") valueName("<file>")
    
    opt[String]('p', "proofs") action { (v, c) => 
      c.copy(inputs = c.inputs ++ (Input(c.directory + v).lines map {c.directory + _})) 
    } text("compress proofs from files listed in <file>\n") valueName("<file>")
    
    arg[String]("<proof-file>...") unbounded() optional() action { (v, c) =>
      c.copy(inputs = c.inputs ++ Seq(c.directory + v)) 
    } text("compress proof from <proof-file>\n")
    
    help("help") text("print this usage text")
    
    note(
    """
    Example:
      The following command processes the proof 'eq_diamond9.smt2' using the
      algorithms 'RP' and the sequential composition of 'DAGify', 'RPI' and 'LU'.
      The compressed proofs are written using 'skeptik' proof format.
      And a csv file containing compression statistics is produced.

      compress -csv -a RP -a (DAGify*RPI*LU) -o skeptik examples/proofs/VeriT/eq_diamond9.smt2
      """)
  }
  
  
  
  def main(args: Array[String]): Unit = {  

    // parser.parse returns Option[C]
    parser.parse(args, Config()) map { c =>
      
      val prettyTable = new HumanReadableTable
      val stats = new CumulativeStats(c.algorithms)
      
      // writing header if file is empty 
      if (c.moutHeader && c.mout.isInstanceOf[FileOutput] && c.mout.asInstanceOf[FileOutput].isEmpty) c.mout.write { 
        "Proof,Uncompressed,,," + (""/:(for (a <- c.algorithms) yield a+",,,")){_ + _} + "\n" +
        ",Length,Width,Height," + (""/:(for (a <- c.algorithms) yield "Length,CoreSize,Height,")){_ + _} + "\n"
      }
  

      c.hout.write("\n")
      
      for (filename <- c.inputs) {
        
        val proofFormat = ("""\.[^\.]+$""".r findFirstIn filename) getOrElse { throw new Exception(unknownFormat(filename)) }
        val proofName = filename.split(proofFormat)(0) // filename without extension
        val proofParser = proofFormat match {
          case ".smt2"  => ProofParserVeriT
          case ".skeptik"  => ProofParserSkeptik
        }
        
        // Reading the proof
        c.hout.write("Reading and checking proof '"+ filename +"' ...")
        val Timed(proof, tRead) = timed { proofParser.read(filename) }   
        c.hout.write(completedIn(tRead) + "\n")
        
        // Measuring the input Proof
        c.hout.write("Measuring...")
        val Timed(mIProof,tMIProof) = timed { measure(proof) }
        c.hout.write(completedIn(tMIProof) + "\n\n")
        
        stats.processInput(proofName, mIProof.toSeq)
        
        // Adding measurements to measurement table
        prettyTable.processInput(proofName, mIProof.toSeq)
        
        // Adding measurements to csv file
        c.mout.write(proofName + mIProof.toSeq.mkString(",",",", ","))
  
        val writeProof =  {
          c.format match {
            case "smt2" => (p: Proof[N], name: String) => ProofExporterVeriT.write(p, name)
            case "skeptik" => (p: Proof[N], name: String) => ProofExporterSkeptik.write(p, name)
            case "" =>  (p: Proof[N], name: String) => { }
          }
        }

        // Compressing the proof
        for (a <- c.algorithms) {
          val algorithm = AlgorithmParser.parse(a)
          c.hout.write("\t\tCompressing with algorithm: " + a + "...")
          val Timed(p, t) = timed { algorithm(proof) }
          c.hout.write(completedIn(t) + "\n")
          
          val oProofName = proofName + "-" + a
          c.hout.write("\t\tWriting compressed proof...")
          val Timed(_,w) = timed { writeProof(p, oProofName) }
          c.hout.write(completedIn(w) + "\n")
          
          c.hout.write("\t\tMeasuring...")
          val Timed(mOProof,tMOProof) = timed { measure(p) }
          c.hout.write(completedIn(tMOProof) + "\n\n")
          
          stats.processOutput(oProofName, a, mOProof.toSeq)
          
          // Adding measurements to csv file
          c.mout.write(mOProof.toSeq.mkString("",",", ","))
             
          prettyTable.processOutput(oProofName, a, mOProof.toSeq)
        }  // end of 'for (a <- algorithms)'
        
        c.mout.write("\n")
      } // end of 'for (filename <- config.inputs)'
      
      // Displaying proof measurements  
      c.hout.write(prettyTable)
      
      // Displaying overall statistics
      c.hout.write(stats)
         
    } getOrElse {
     
    } 
  }
  
  abstract class DataAggregator {
    def processInput(name:String, measurements: Seq[Int])
    def processOutput(name:String, a: String, measurements: Seq[Int])
  }
      
  // measurement table initialized with its header only
  // rows with data for every input or output proof are added during execution 
  // and the table is displayed to the user (to hout) at the end
  class HumanReadableTable extends DataAggregator {
    private var t: Seq[Seq[Any]] = Seq(Seq("Proof", "Length", "CoreSize", "Height"))
    private var currentInputMeasurements: Seq[Int] = null
    private def append(name: String, data: Seq[Any]) = {
      val row = Seq(name) ++ data
      t ++= Seq(row)
    }
    def processInput(name: String, measurements: Seq[Int]) = {
      currentInputMeasurements = measurements
      append(name, measurements)
    }
    def processOutput(name: String, a: String, measurements: Seq[Int]) = {
      val data = (currentInputMeasurements zip measurements) map { case (i,o) => 
        ""+ o + " (" + (Math.round(1000.0*o/i)/10.0) + "%)"
      }  
      append(name, data)
    }
    
    override def toString = {
      "\n" + at.logic.skeptik.util.pretty.prettyTable(t) + """ 
      where:           
        Length = number of inferences in the proof
        CoreSize = number of axioms in the proof
        Height = length of longest path from leaf to root         
      """ + "\n"
    }
  }

  class CumulativeStats(algorithms: Seq[String]) extends DataAggregator {
    private val m = MMap( ("id"::(algorithms.toList)) map { (_ -> Seq(0,0,0)) } :_* )
    
    def processInput(name: String, measurements: Seq[Int]) = append("id", measurements) 
    
    def processOutput(name: String, a: String, measurements: Seq[Int]) = append(a, measurements)
    
    private def append(a: String, d: Seq[Int]) = m(a) = (m(a) zip d) map { case (x,y) => x+y }
    
    private def normalize(a: String) = (m(a) zip m("id")) map {case (o,i) => (Math.round(1000.0*o/i)/10.0)}
    
    override def toString = {
      ("" /: m.keys) { (s, k) => s + "Cumulatives stats for " + k + ": " + normalize(k).mkString("", "%, ", "%") + "\n" }
    }
    
  }
  
}
