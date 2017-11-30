import java.util
import java.util.Random

import model.VehicleType._
import model.{Game, Move, Player, TerrainType, Vehicle, VehicleType, WeatherType, World}

import scala.collection.convert.ImplicitConversionsToScala._
import scala.collection.mutable
import scala.language.implicitConversions

final class MyStrategy extends Strategy with WorldAware with TerrainAndWeather {

  final private val vehicleById = new util.HashMap[Long, Vehicle]
  final private val updateTickByVehicleId = new util.HashMap[Long, Integer]
  final private val delayedMoves = new util.ArrayDeque[Action]
  private var random: Random = _
  private var terrainTypeByCellXY: Array[Array[TerrainType]] = _
  private var weatherTypeByCellXY: Array[Array[WeatherType]] = _
  private var me: Player = _
  var world: World = _
  var game: Game = _
  private var move: Move = _
  private val buildings = new mutable.HashMap[Long, Building]()

  /**
    * Основной метод стратегии, осуществляющий управление армией. Вызывается каждый тик.
    *
    * @param me    Информация о вашем игроке.
    * @param world Текущее состояние мира.
    * @param game  Различные игровые константы.
    * @param move  Результатом работы метода является изменение полей данного объекта.
    */
  override def move(me: Player, world: World, game: Game, move: Move) {
    initializeStrategy(world, game)
    initializeTick(me, world, game, move)
    if (me.getRemainingActionCooldownTicks > 0)
      return
    if (executeDelayedMove())
      return
    makeMove()
    executeDelayedMove()
  }

  /**
    * Инциализируем стратегию.
    * <p>
    * Для этих целей обычно можно использовать конструктор, однако в данном случае мы хотим инициализировать генератор
    * случайных чисел значением, полученным от симулятора игры.
    */
  private def initializeStrategy(world: World, game: Game): Unit = if (random == null) {
    random = new Random(game.getRandomSeed)
    terrainTypeByCellXY = world.getTerrainByCellXY
    weatherTypeByCellXY = world.getWeatherByCellXY
  }

  /**
    * Сохраняем все входные данные в полях класса для упрощения доступа к ним, а также актуализируем сведения о каждой
    * технике и времени последнего изменения её состояния.
    */
  private def initializeTick(me: Player, world: World, game: Game, move: Move): Unit = {
    this.me = me
    this.world = world
    this.game = game
    this.move = move
    for (vehicle <- world.getNewVehicles) {
      vehicleById.put(vehicle.getId, vehicle)
      updateTickByVehicleId.put(vehicle.getId, world.getTickIndex)
    }
    for (vehicleUpdate <- world.getVehicleUpdates) {
      val vehicleId = vehicleUpdate.getId
      if (vehicleUpdate.getDurability == 0) {
        vehicleById.remove(vehicleId)
        updateTickByVehicleId.remove(vehicleId)
      }
      else {
        vehicleById.put(vehicleId, new Vehicle(vehicleById.get(vehicleId), vehicleUpdate))
        updateTickByVehicleId.put(vehicleId, world.getTickIndex)
      }
    }

    world.getFacilities.foreach { f =>
      buildings.put(f.getId, Building(f))
    }

    captureGroups.foreach { group =>
      group.vehicles =
        vehicleById.values()
          .filter(_.getGroups.contains(group.groupNumber))
          .toList
    }
    captureGroups = captureGroups.filter(_.isAlive)
    captureGroups
      .filter(g => isMy(g.building))
      .foreach(_.building = null)
  }

  private def isMy(b: Building) = b.ownerPlayerId == world.getMyPlayer.getId

  /**
    * Достаём отложенное действие из очереди и выполняем его.
    *
    * @return Возвращает { @code true}, если и только если отложенное действие было найдено и выполнено.
    */
  private def executeDelayedMove(): Boolean = {
    val delayedMove = delayedMoves.poll
    if (delayedMove == null) {
      false
    }
    else {
      println(s"[${world.getTickIndex}]: $delayedMove")
      delayedMove.action(move)
      true
    }
  }

  private implicit def richList(list: Seq[Double]) = new {
    def average: Option[Double] =
      if (list.nonEmpty) Some(list.sum / list.length)
      else None
  }

  /**
    * Основная логика нашей стратегии.
    */
  private def makeMove(): Unit = {
    if (world.getTickIndex == 0) {
      initAirNetwork()
    } else if (world.getMyPlayer.getRemainingNuclearStrikeCooldownTicks == 0) {
      val targets = opponentUnits
        .map(GameMap.vehicleToSquare)
        .groupBy(identity)
        .toList
        .sortBy(_._2.size)
        .reverse
        .map(_._1)
        .map(GameMap.squareCenter)

      val spotters = myUnits
      val targetOption = (for {
        target <- targets
        spotter <- spotters
        if spotter.getDistanceTo(target._1, target._2) <= getActualVisionRange(spotter)
      } yield (target, spotter)).headOption

      targetOption.forall {
        case ((x, y), spotter) =>
          delayedMoves.add(NuclearStrike(x, y, spotter.getId))
      }
    }
  }

  private def selectAll(vehicleType: VehicleType) =
    Select(0, 0, world.getWidth, world.getHeight, vehicleType)

  private def myUnits = vehicleById.values.filter { v => v.getPlayerId == me.getId }

  private def my(vType: VehicleType) =
    myUnits.filter(_.getType == vType)

  private def opponentUnits = vehicleById.values.filter { v => v.getPlayerId != me.getId }


  private def initAirNetwork(): Unit =
    if (buildings.isEmpty) {
      Seq(FIGHTER, HELICOPTER, TANK, IFV, ARRV).zipWithIndex
        .foreach {
          case (t, i) =>
            val vehicles = my(t)
            val xs = vehicles.map(_.getX)
            val ys = vehicles.map(_.getY)
            val minX = xs.min
            val minY = ys.min
            val startX = minX + i * 5
            val startY = minY + i * 5
            Seq(selectAll(t),
              GoTo(startX, startY),
              Scale(minX, minY, 10.0))
              .foreach(delayedMoves.add)
        }
    } else {
      Seq(FIGHTER, HELICOPTER).zipWithIndex
        .foreach {
          case (t, i) =>
            val vehicles = my(t)
            val xs = vehicles.map(_.getX)
            val ys = vehicles.map(_.getY)
            val minX = xs.min
            val minY = ys.min
            val startX = minX + i * 5
            val startY = minY + i * 5
            Seq(selectAll(t),
              GoTo(startX, startY),
              Scale(minX, minY, 10.0))
              .foreach(delayedMoves.add)
        }
      Seq(IFV, TANK, ARRV).foreach { t =>
        val vehicles = my(t)
        val xs = vehicles.map(_.getX)
        val ys = vehicles.map(_.getY)
        val minX = xs.min
        val minY = ys.min
        val maxX = xs.max
        val maxY = ys.max
        val centerX = (maxX - minX) / 2
        val centerY = (maxY - minY) / 2
        assignGroup(Point(minX, minY), Point(centerX, centerY), t)
        assignGroup(Point(centerX, minY), Point(maxX, centerY), t)
        assignGroup(Point(minX, centerY), Point(centerX, maxY), t)
        assignGroup(Point(centerX, centerY), Point(maxX, maxY), t)
      }
    }

  private var groupNumber = 0

  private var groups: List[Int] = Nil

  private var captureGroups: List[CaptureGroup] = Nil

  private def nextGroupNumber: Int = {
    groupNumber += 1
    groupNumber
  }

  private def assignGroup(leftTop: Point, rightBottom: Point, vehicleType: VehicleType): Unit = {
    delayedMoves.add(Select(leftTop.x, leftTop.y, rightBottom.x, rightBottom.y, vehicleType))
    val number = nextGroupNumber
    delayedMoves.add(Assign(number))
    groups = number :: groups
    captureGroups = new CaptureGroup(number) :: captureGroups
  }
}
