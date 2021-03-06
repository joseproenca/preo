package preo.backend

import preo.ast.CPrim
import preo.backend.PortAutomata.{Port, State, Trans}
import preo.backend.Network.Prim
import preo.common.{GenerationException, TimeoutException, TypeCheckException}
import preo.frontend.Show

/**
  * Representation of an automata, aimed at being generated from a [[Network]].
 *
  * @param ports Represent the possible labels (actions)
  * @param init  Initial state
  * @param trans Transitions - Relation between input and output states, with associated
  *              sets of actions and of edges (as in [[Network.Prim]]).
  */
case class PortAutomata(ports:Set[Port],init:Int,trans:Trans)
  extends Automata {

  /** Collects all states, seen as integers */
  override def getStates: Set[State] = (for((x,(y,_,_,_)) <- trans) yield Set(x,y)).flatten + init
  // states: ints, transitions: maps from states to (new state,ports fired, primitives involved)

  /** Returns the initial state */
  override def getInit: State = init

  /** Returns the transitions to be displayed */
  override def getTrans(fullName:Boolean = false): Automata.Trans =
    for ((from, (to, fire, es, anim)) <- trans)
      yield (
          from
        , es.map(getName(_,fire))
            .filterNot(s => s=="sync" || s=="sync↓" || s=="sync↑" || s=="sync↕")
            .foldRight[Set[String]](Set())(cleanDir)
            .mkString(".") +
          //"~"+anim.mkString(" ")+ // TODO: uncomment to share animation
          "§"+
          fire.mkString("§")
//          fire.map(_.toString+"§").mkString + anim
        , (fire,es).hashCode().toString
        , to)

  private def getName2(edge: Prim, fire:Set[Port]):String =
    s"${edge.prim.name}-${edge.prim.extra}-${edge.parents.mkString("/")}-${fire.mkString(":")}"

  private def getName3(edge: Prim, fire:Set[Port]):String = {
    getName(edge,fire)+"~"+(edge.ins:::edge.outs).toSet.mkString("~")
  }
  private def getName(edge: Prim, fire:Set[Port]):String = (edge.parents match {
    case Nil     => primName(edge.prim)
    case ""::_   => primName(edge.prim)
    case head::_ => head
  }) + getDir(edge,fire) //+
  //  s"[${edge.ins.toSet.intersect(fire).mkString("|")}->${edge.outs.toSet.intersect(fire).mkString("|")}]"
  //  fire.mkString("|")
  private def getDir(edge: Prim, fire:Set[Port]): String = {
    val src = (edge.ins.toSet intersect fire).nonEmpty
    val snk = (edge.outs.toSet intersect fire).nonEmpty
    (src,snk) match {
      case (true,false) => "↓"
      case (false,true) => "↑"
      case _ => "↕"
    }
  }
  private def primName(prim: CPrim): String = (prim.name,prim.extra.toList) match {
    case ("writer",List(s:String)) => s"wr($s)"
    case ("reader",List(s:String)) => s"rd($s)"
    case (n,Nil) => n
    case (n,l) => s"$n(${l.mkString(",")})"
  }
  private def cleanDir(s:String,rest:Set[String]): Set[String] = (s.init,s.last) match {
    case (name,'↓') if rest.contains(name + '↑') || rest.contains(name + '↕') =>
      rest - (name+'↓') - (name+'↑') + (name+'↕')
    case (name,'↑') if rest.contains(name + '↓') || rest.contains(name + '↕') =>
      rest - (name+'↓') - (name+'↑') + (name+'↕')
    case _ => rest + s
  }

  private def printPrim(edge: Prim):String = {
    s"""${edge.prim.name}-${edge.prim.i.ports}-${edge.prim.j.ports}-${edge.ins.mkString(".")}-${edge.outs.mkString(".")}"""
  }

  /**
    * Remove unreachable states (traverse from initial state)
    * @return automata without unreachable states
    */
  private def cleanup: PortAutomata = {
    var missing = Set(init)
    var done = Set[Int]()
    var ntrans: Trans = Set()
    while (missing.nonEmpty) {
      val next = missing.head
      missing = missing.tail
      done += next
      for (t@(from, (to, _, _,_)) <- trans if from == next) {
        ntrans += t
        if (!(done contains to)) missing += to
      }
    }
    PortAutomata(ports, init, ntrans)
  }

  /** List transitions in a pretty-print. */
  def show: String =
    s"$init:\n"+trans.map(x=>s" - ${x._1}->${x._2._1} "+
      s"${x._2._2.toList.sorted.mkString("[",",","]")} "+
      s"${x._2._3.toList.map(_.prim.name).sorted.mkString("(",",",")")}").mkString("\n")

  def smallShow: String = {
    trans.flatMap(_._2._3).toList.map(_.prim.name).sorted.mkString("Aut(",",",")")
  }

}

object PortAutomata {

  type Trans = Set[(State,(State,Set[Port],Set[Prim],Animation))]
  type Port = Int
  type State = Int

  case class Loc(p:Port*) {
    override def toString: String = p.mkString(".")
  }
  sealed abstract class AnimStep {
    override def toString: String = this match {
      case Move(from, to) => from+">"+to
      case Dupl(at) => at+"*2"
      case Del(at) => at+"*0"
      case Create(at) => at+"!"
    }
  }
  case class Move(from:Loc,to:Loc) extends AnimStep
  case class Dupl(at:Loc) extends AnimStep
  case class Del(at:Loc) extends AnimStep
  case class Create(at:Loc) extends AnimStep
  type Animation = List[AnimStep]

  /** How to build basic Port automata */
  implicit object PortAutomataBuilder extends AutomataBuilder[PortAutomata] {

    /** Given an edge between two nodes (ports), builds a primitive automata for the connector in its edge.
      * Only recognises primitive connectors.
      *
      * @param e edge with primitive and ports
      * @param seed current counter used to generate state names
      * @return new PortAutomata and updated counter for state names
      */
    def buildAutomata(e: Prim, seed: Int): (PortAutomata, Int) = e match {
      // if prim has ports with same name (selfloop) then return an emtpy automaton
      case Prim(CPrim(_, _, _, _), ins, outs,_) if (ins++outs).groupBy(p=>p).exists(g=>g._2.size>1) => (emptyAutomata,seed)
      case Prim(CPrim("sync", _, _, _), List(a), List(b),_) =>
        (PortAutomata(Set(a, b), seed, Set(seed -> (seed, Set(a, b), Set(e), List(Move(Loc(a),Loc(b)))))), seed + 1)
      case Prim(CPrim("id", _, _, _), List(a), List(b),_) =>
        (PortAutomata(Set(a, b), seed, Set(seed -> (seed, Set(a, b), Set(e), List(Move(Loc(a),Loc(b)))))), seed + 1)
      case Prim(CPrim("lossy", _, _, _), List(a), List(b),_) =>
        (PortAutomata(Set(a, b), seed, Set(seed -> (seed, Set(a, b), Set(e), List(Move(Loc(a),Loc(a,b)))),
                                           seed -> (seed, Set(a), Set(e), List(Move(Loc(a),Loc(a,b)),Del(Loc(a,b)))))), seed + 1)
      case Prim(CPrim("fifo", _, _, _), List(a), List(b),_) =>
        (PortAutomata(Set(a, b), seed - 1, Set(seed - 1 -> (seed, Set(a), Set(e), List(Move(Loc(a),Loc(a,b)),Del(Loc(a,b)))),
                                               seed -> (seed - 1, Set(b), Set(e), List(Create(Loc(a,b)),Move(Loc(a,b),Loc(a)))))), seed + 2)
      case Prim(CPrim("fifofull", _, _, _), List(a), List(b),_) =>
        (PortAutomata(Set(a, b), seed, Set(seed - 1 -> (seed, Set(a), Set(e),List(Create(Loc(a,b)),Move(Loc(a,b),Loc(a)))),
                                           seed -> (seed - 1, Set(b), Set(e),List(Move(Loc(a),Loc(a,b)),Del(Loc(a,b)))) )), seed + 2)
      case Prim(CPrim("drain", _, _, _), List(a, b), List(),_) =>
        (PortAutomata(Set(a, b), seed, Set(seed -> (seed, Set(a, b), Set(e),
             List(Move(Loc(a),Loc(a,b)),Move(Loc(b),Loc(a,b)),Del(Loc(a,b)),Del(Loc(a,b)))))), seed + 1)
      // deprecated - using nodes instead
      case Prim(CPrim("merger", _, _, _), List(a, b), List(c),_) =>
        (PortAutomata(Set(a, b, c), seed, Set(seed -> (seed, Set(a, c), Set(e),
             List(Move(Loc(a),Loc(c)))), seed -> (seed, Set(b, c), Set(e), List(Move(Loc(b),Loc(c)))))), seed + 1)
      // deprecated - using nodes instead
      case Prim(CPrim("dupl", _, _, _), List(a), List(b, c),_) =>
        (PortAutomata(Set(a, b, c), seed, Set(seed -> (seed, Set(a, b, c), Set(e),
             List(Dupl(Loc(a)),Move(Loc(a),Loc(b)),Move(Loc(a),Loc(c)))))), seed + 1)
      case Prim(CPrim("writer", _, _, _), List(), List(a),_) =>
        (PortAutomata(Set(a), seed, Set(seed -> (seed, Set(a), Set(e),
             List(Create(Loc(a)))))), seed + 1)
      case Prim(CPrim("reader", _, _, _), List(a), List(),_) =>
        (PortAutomata(Set(a), seed, Set(seed -> (seed, Set(a), Set(e),
             List(Del(Loc(a)))))), seed + 1)
      case Prim(CPrim("noSnk", _, _, _), List(), List(a),_) =>
        (PortAutomata(Set(a), seed, Set()), seed + 1)
      case Prim(CPrim("noSrc", _, _, _), List(a), List(),_) =>
        (PortAutomata(Set(a), seed, Set()), seed + 1)
      case Prim(CPrim("timer", _, _, _), List(a), List(b),extra) =>
        throw new GenerationException(s"Connector with time to be interpreted only as an IFTA instance")
      // unknown name with type 1->1 -- behave as identity
      case Prim(CPrim(name, _, _, _), List(a), List(b),_) =>
        (PortAutomata(Set(a, b), seed, Set(seed -> (seed, Set(a, b), Set(e),
             List(Move(Loc(a),Loc(b)))))), seed + 1)

      // variable connectors only ment for ifta
      case Prim(CPrim("node",_,_,extra), _, _, _) if extra.intersect(Set("vdupl","vmrg","vxor")).nonEmpty =>
        throw new GenerationException(s"Connector with variable parts ${extra.mkString(",")}, to be interpreted only as an IFTA instance")
      // new version uses nodes instead of dupl/merger
      case Prim(CPrim("node",_,_,extra), ins, outs, _) if extra contains "dupl" =>
        val is = ins.toSet
        val os = outs.toSet
        (PortAutomata(is ++ os, seed
          , for (i <- is) yield
            seed -> (seed, os+i, Set(e),
              (for (_<-1 until os.size) yield Dupl(Loc(i))).toList ++
              (for (o<-os) yield Move(Loc(i),Loc(o))).toList))
          , seed + 1)
      case Prim(CPrim("node",_,_,extra), ins, outs, _)  => // xor node - only for virtuoso so far...
        val i = ins.toSet
        val o = outs.toSet
        (PortAutomata(i ++ o, seed
          , for (xi <- i; xo <- o) yield
            seed -> (seed, Set(xi,xo), Set(e),
              List(Move(Loc(xi),Loc(xo)))))
          , seed + 1)


      case Prim(p, _, _,_) =>
        throw new GenerationException(s"Unknown port automata for primitive $p")

    }

    def emptyAutomata = PortAutomata(Set(), 0, Set())

    /**
      * Automata composition - combining every possible transition,
      * and including transitions that can occur in parallel.
      * @param a1 automata to be composed
      * @param a2 automata to be composed
      * @return composed automata
      */
    def join(a1:PortAutomata,a2:PortAutomata): PortAutomata = join(a1,a2,20000)

    def join(a1:PortAutomata,a2:PortAutomata,timeout:Int): PortAutomata = {
      //     println(s"combining ${this.show}\nwith ${other.show}")
      var seed = 0
      var steps = timeout
      val shared = a1.ports.intersect(a2.ports)
      var restrans = Set[(Int,(Int,Set[Int],Set[Prim],Animation))]()
      var newStates = Map[(Int,Int),Int]()
      def mkState(i1:Int,i2:Int) = if (newStates.contains((i1,i2)))
        newStates((i1,i2))
      else {
        seed +=1
        newStates += (i1,i2) -> seed
        seed
      }
      def tick(): Unit =  {
        steps-=1
        if (steps==0) throw new
            TimeoutException(s"When composing automata:\n - ${a1.smallShow}\n - ${a2.smallShow}")
      }
      def ok(toFire:Set[Int]): Boolean = {
        tick()
        toFire.intersect(shared).isEmpty
      }
      def ok2(toFire1:Set[Int],toFire2:Set[Int]): Boolean = {
        tick()
        toFire1.intersect(a2.ports) == toFire2.intersect(a1.ports)
      }

      // just 1
      for ((from1,(to1,fire1,es1,anim1)) <- a1.trans; p2 <- a2.getStates)
        if (ok(fire1))
          restrans += mkState(from1,p2) -> (mkState(to1,p2),fire1,es1,anim1)
      // just 2
      for ((from2,(to2,fire2,es2,anim2)) <- a2.trans; p1 <- a1.getStates)
        if (ok(fire2))
          restrans += mkState(p1,from2) -> (mkState(p1,to2),fire2,es2,anim2)
      // communication
      for ((from1,(to1,fire1,es1,anim1)) <- a1.trans; (from2,(to2,fire2,es2,anim2)) <- a2.trans) {
        if (ok2(fire1,fire2))
          restrans += mkState(from1,from2) -> (mkState(to1,to2),fire1++fire2,es1++es2, anim1++anim2)
      }
      // println(s"ports: $newStates")
      val res1 = PortAutomata(a1.ports++a2.ports,mkState(a1.init,a2.init),restrans)
      //    println(s"got ${a.show}")
      val res2 = res1.cleanup
      //    println(s"cleaned ${a2.show}")
//      println(s"${res2.smallShow} -> ${timeout-steps}\n===${res2.show}")
      res2
    }

  }

}

