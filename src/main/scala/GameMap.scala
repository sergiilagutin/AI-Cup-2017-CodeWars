import model.Vehicle

object GameMap {

  val SQUARE_SIZE = 32.0
  val SQUARE_COUNT = 32

  def pointToSquare(x: Double, y: Double): (Int, Int) =
    ((x / SQUARE_SIZE).toInt, (y / SQUARE_SIZE).toInt)

  def vehicleToSquare(v: Vehicle): (Int, Int) =
    pointToSquare(v.getX, v.getY)
}
