# Last Mile

> Disclaimer: This project was vibecoded for fun.

**Last Mile** is a 2D freight railway management game built with Java Swing. Lay rail across a freeform map, connect stations, schedule trains, and deliver freight before contracts expire.

## Gameplay

The game starts with a regional railway, the Juniper locomotive, and a timber shipment already in transit. Your company earns money by completing freight contracts.

1. Accept an available order on the Freight Board.
2. Select a train and add the contract's origin and destination to its timetable.
3. Make sure the origin has stock and the train has enough spare cargo capacity.
4. Let the train collect the crates and deliver them before the deadline.
5. Use the payout to expand your railway.

Stations produce their listed goods over time. Trains load accepted orders automatically when they stop at the origin, and unload them at the destination. Each carriage holds four crates. Timetables can have up to five stops and can repeat or run once.

## Railway Tools

| Tool | What it does | Cost |
| --- | --- | ---: |
| Lay track | Draw connected, freeform track sections. Nearby endpoints snap together and crossings connect as junctions. | $12 per section |
| Block signals | Add a basic occupancy signal beside a rail. A train waits if another train occupies the same protected section. | $55 |
| Found station | Place a station anywhere on the map. Stations placed near track snap to it. | $175 |
| Buy locomotive | Deploy a train next to existing track. | $425 |
| Set timetable | Add stations to the selected train's ordered route. | Free |
| Remove objects | Remove trains, stations, signals, or track for a partial refund. | None |

Removal is blocked when it would disrupt active gameplay: trains carrying freight, the last locomotive, stations used by active orders or routes, and track used by a train or its carriages are protected.

## Controls

| Input | Action |
| --- | --- |
| `1`–`6` | Select a railway tool |
| Mouse click | Use the selected tool or select a train, order, or timetable stop |
| Track tool: click twice | Start and finish a track section; keep clicking to continue the route |
| `Esc` | Finish drawing track, or close the guide when it is open |
| Space | Pause or resume the simulation |
| Mouse wheel over map | Zoom in or out around the pointer |
| Map `+` / `-` buttons | Zoom in or out |
| Right-drag over map | Pan the view |
| Click a timetable row, then `Up` / `Down` | Move the selected stop |
| `Backspace` or `Delete` | Remove the selected timetable stop |
| `F1` or **Book** | Open the in-game guide |
| `Ctrl+S` or **Save** | Save the railway to an `.lmsave` file |
| `Ctrl+O` or **Load** | Load a saved railway |

The window can be resized; the interface keeps its proportions and adds space around the game where needed.

## Requirements

- Java 21
- The included Gradle wrapper

## Build and Run

From the repository root, build the application and run its tests:

```bash
./gradlew build
./gradlew test
```

Start the desktop game with:

```bash
./gradlew run
```

The application JAR is created at `app/build/libs/app.jar` and can be launched with:

```bash
java -jar app/build/libs/app.jar
```

On Windows, use `gradlew.bat` instead of `./gradlew`.