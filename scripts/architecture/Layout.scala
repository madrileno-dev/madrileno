package architecture

final case class Point(x: Double, y: Double)

object Layout {
  private final case class Walk(visited: Set[String], kept: List[(String, String)])

  def layered(nodes: List[String], edges: List[(String, String)]): Map[String, Point] = {
    val sorted    = nodes.distinct.sorted
    val known     = sorted.toSet
    val adjacency = edges.filter((from, to) => from != to && known(from) && known(to)).distinct.groupMap(_._1)(_._2)
    val dag       = sorted.foldLeft(Walk(Set.empty, Nil))((walk, node) => visit(node, adjacency, Set.empty, walk)).kept
    val layerOf   = longestPath(sorted, dag)
    val ordered   = barycenter(layerOf, dag)
    ordered.flatMap { (layer, row) =>
      row.zipWithIndex.map((node, index) => node -> Point(40.0 + index * 200.0, 40.0 + layer * 120.0))
    }.toMap
  }

  private def visit(
    node: String,
    adjacency: Map[String, List[String]],
    path: Set[String],
    walk: Walk
  ): Walk =
    if (walk.visited(node)) walk
    else
      adjacency.getOrElse(node, Nil).sorted.foldLeft(walk.copy(visited = walk.visited + node)) { (current, next) =>
        if ((path + node)(next)) current
        else visit(next, adjacency, path + node, current.copy(kept = (node -> next) :: current.kept))
      }

  private def longestPath(nodes: List[String], dag: List[(String, String)]): Map[String, Int] =
    nodes.indices.foldLeft(nodes.map(_ -> 0).toMap) { (layers, _) =>
      dag.foldLeft(layers)((acc, edge) => acc.updated(edge._2, math.max(acc(edge._2), acc(edge._1) + 1)))
    }

  private def barycenter(layerOf: Map[String, Int], dag: List[(String, String)]): List[(Int, List[String])] = {
    val initial = layerOf.toList.groupMap(_._2)(_._1).view.mapValues(_.sorted).toMap
    val depth   = initial.keys.maxOption.getOrElse(0)
    val down    = (1 to depth).foldLeft(initial)((rows, layer) =>
      rows.updated(layer, reorder(rows.getOrElse(layer, Nil), rows.getOrElse(layer - 1, Nil), node => dag.collect { case (from, `node`) => from }))
    )
    val up = (depth - 1 to 0 by -1).foldLeft(down)((rows, layer) =>
      rows.updated(layer, reorder(rows.getOrElse(layer, Nil), rows.getOrElse(layer + 1, Nil), node => dag.collect { case (`node`, to) => to }))
    )
    up.toList.sortBy(_._1)
  }

  private def reorder(
    row: List[String],
    neighbourRow: List[String],
    neighbours: String => List[String]
  ): List[String] = {
    val position = neighbourRow.zipWithIndex.toMap
    row.zipWithIndex
      .sortBy { (node, index) =>
        val known = neighbours(node).flatMap(position.get)
        (if (known.isEmpty) index.toDouble else known.sum.toDouble / known.size, node)
      }
      .map(_._1)
  }
}
