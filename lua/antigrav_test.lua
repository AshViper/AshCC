-- Anti-gravity core test program. Put the core on a Sable ship with this computer next to it.
--   antigrav_test              automatic test: API checks, hover, climb, move, local velocity, turn, gravity, power off
--   antigrav_test --no-fall    skip the steps that let the ship drop (negative gravity and power off)
-- The ship moves up to about 4 blocks up / sideways and drops about 2 blocks; it ends where it started.
-- Results go to the screen and to antigrav_test.log. Motion is measured, so speeds count within 10%.
local args = { ... }
local noFall = false
for _, a in ipairs(args) do
  if a == "--no-fall" then noFall = true end
end
local LOG = "antigrav_test.log"

local core = peripheral.find("antigravity")
if not core then error("no antigravity core attached", 0) end

local logFile = fs.open(LOG, "w")
local pass, fail = 0, 0
local function log(fmt, ...)
  local line = string.format(fmt, ...)
  print(line)
  logFile.writeLine(line)
  logFile.flush()
end
local function check(ok, fmt, ...)
  if ok then pass = pass + 1 else fail = fail + 1 end
  log("[%s] " .. fmt, ok and "PASS" or "FAIL", ...)
  return ok
end
local function near(a, b, tol) return math.abs(a - b) <= tol end
local function wrap(a) return (a + 180) % 360 - 180 end
local function dist(p, q) return math.sqrt((p.x - q.x) ^ 2 + (p.y - q.y) ^ 2 + (p.z - q.z) ^ 2) end

-- Polls instead of waiting for antigravity_arrived: peripheral calls drop events that arrive while they wait.
local function waitTarget(timeout)
  local t0 = os.clock()
  while os.clock() - t0 < timeout do
    if core.isAtTarget() then return os.clock() - t0 end
    sleep(0.1)
  end
  return nil
end

-- Brakes and waits until the ship is still (or timeout).
local function settle(timeout)
  core.stop()
  local t0 = os.clock()
  while os.clock() - t0 < (timeout or 4) do
    local v, w = core.getVelocity(), core.getAngularVelocity()
    if math.sqrt(v.x ^ 2 + v.y ^ 2 + v.z ^ 2) < 0.05 and math.abs(w.yawRate) + math.abs(w.pitchRate) + math.abs(w.rollRate) < 0.5 then
      return true
    end
    sleep(0.1)
  end
  return false
end

local function run()
  ---------------------------------------------------------------- API
  log("== API")
  check(peripheral.getType(core) == "antigravity", "peripheral type")
  local id = core.getSubLevel()
  if not check(type(id) == "string" and #id == 36, "on a ship: %s", tostring(id)) then
    log("the core must be on a Sable ship; stopping")
    return
  end
  core.setEnabled(true)
  core.setGravity(0)
  sleep(0.2)
  check(core.isEnabled(), "isEnabled")
  check(core.isActive(), "isActive (no redstone, first core on the ship)")
  check(core.getMass() > 0, "mass %.1f", core.getMass())
  local s = core.getState()
  check(s.position and s.rotation and s.velocity and s.linear and s.angular, "getState has position/rotation/velocity/modes")
  check(not pcall(core.setVelocity, 0 / 0, 0, 0), "setVelocity(nan) is rejected")
  check(not pcall(core.setGravity, math.huge), "setGravity(inf) is rejected")
  check(not pcall(core.setLimits, { speed = "fast" }), "setLimits{speed=\"fast\"} is rejected")

  local limits = core.getLimits()
  core.setLimits({ speed = 1e9 })
  check(core.getLimits().speed == limits.max.speed, "speed limit is clamped to the cap %.0f", limits.max.speed)
  core.setLimits({ speed = 10, acceleration = 20, angularSpeed = 45, angularAcceleration = 90 })
  local l = core.getLimits()
  check(l.speed == 10 and l.acceleration == 20 and l.angularSpeed == 45 and l.angularAcceleration == 90, "setLimits sets all four")

  ---------------------------------------------------------------- hover
  log("== hover")
  check(settle(5), "ship comes to rest")
  local home = core.getPosition()
  local homeRot = core.getRotation()
  log("   home %.2f %.2f %.2f  yaw %.1f pitch %.1f roll %.1f", home.x, home.y, home.z, homeRot.yaw, homeRot.pitch, homeRot.roll)
  sleep(1)
  local p = core.getPosition()
  check(dist(p, home) < 0.1, "stays put weightless for 1 s (moved %.3f)", dist(p, home))

  ---------------------------------------------------------------- climb
  log("== velocity")
  core.setVelocity(0, 3, 0)
  sleep(1.5)
  local v = core.getVelocity()
  check(near(v.y, 3, 0.3) and math.abs(v.x) < 0.2 and math.abs(v.z) < 0.2, "setVelocity(0,3,0): %.2f %.2f %.2f", v.x, v.y, v.z)
  check(settle(), "stop() brakes to rest")

  log("== move")
  local from = core.getPosition()
  core.move(0, 2, 0)
  check(core.getState().linear.mode == "position", "move() switches to position mode")
  check(waitTarget(10) ~= nil, "move(0,2,0) arrives")
  p = core.getPosition()
  check(near(p.y - from.y, 2, 0.2) and near(p.x, from.x, 0.2) and near(p.z, from.z, 0.2), "moved up %.2f", p.y - from.y)

  local side = { x = home.x + 2, y = home.y + 1, z = home.z }
  core.moveTo(side.x, side.y, side.z)
  check(waitTarget(10) ~= nil, "moveTo(home + (2,1,0)) arrives")
  check(dist(core.getPosition(), side) < 0.2, "at the point (off by %.2f)", dist(core.getPosition(), side))

  ---------------------------------------------------------------- local velocity
  log("== local velocity")
  core.setLocalVelocity(0, 0, 2)
  sleep(1.2)
  v = core.getVelocity()
  check(near(v["local"].forward, 2, 0.2) and math.abs(v["local"].right) < 0.2 and math.abs(v["local"].up) < 0.2,
    "setLocalVelocity(0,0,2): right %.2f up %.2f forward %.2f", v["local"].right, v["local"].up, v["local"].forward)
  settle()

  ---------------------------------------------------------------- rotation
  log("== rotation")
  local yaw0 = core.getRotation().yaw
  core.setRotation(yaw0 + 90)
  check(waitTarget(12) ~= nil, "setRotation(yaw + 90) arrives")
  local r = core.getRotation()
  check(near(wrap(r.yaw - yaw0), 90, 2) and math.abs(r.pitch) < 2 and math.abs(r.roll) < 2,
    "turned %.1f deg (pitch %.1f roll %.1f)", wrap(r.yaw - yaw0), r.pitch, r.roll)

  -- Forward must have turned with the ship: forward velocity now goes 90 degrees clockwise from before
  core.setLocalVelocity(0, 0, 2)
  sleep(1.2)
  v = core.getVelocity()
  local heading = math.deg(math.atan2(v.x, -v.z))
  check(near(wrap(heading - r.yaw), 0, 5), "forward follows the heading (moving towards %.1f, facing %.1f)", heading, r.yaw)
  settle()

  core.setAngularVelocity(30, 0, 0)
  sleep(1.5)
  local w = core.getAngularVelocity()
  local y1 = core.getRotation().yaw
  sleep(1)
  local turned = wrap(core.getRotation().yaw - y1)
  check(near(w.yawRate, 30, 3), "setAngularVelocity(30,0,0): yawRate %.1f", w.yawRate)
  check(near(turned, 30, 3), "turned %.1f deg in 1 s", turned)

  core.setRotation(homeRot.yaw, 10, -5)
  check(waitTarget(12) ~= nil, "setRotation(home yaw, pitch 10, roll -5) arrives")
  r = core.getRotation()
  check(near(wrap(r.yaw - homeRot.yaw), 0, 2) and near(r.pitch, 10, 2) and near(r.roll, -5, 2),
    "yaw %.1f pitch %.1f roll %.1f", r.yaw, r.pitch, r.roll)
  core.level()
  check(waitTarget(12) ~= nil, "level() arrives")
  r = core.getRotation()
  check(math.abs(r.pitch) < 2 and math.abs(r.roll) < 2, "level: pitch %.1f roll %.1f", r.pitch, r.roll)

  ---------------------------------------------------------------- hold / release
  log("== hold / release")
  settle()
  core.hold()
  local held = core.getPosition()
  sleep(1)
  check(dist(core.getPosition(), held) < 0.1, "hold() keeps the position (moved %.3f)", dist(core.getPosition(), held))
  core.release()
  local st = core.getState()
  check(st.linear.mode == "free" and st.angular.mode == "free", "release() frees both")
  local before = core.getPosition()
  sleep(1)
  check(dist(core.getPosition(), before) < 0.3, "weightless and free: drifts %.3f in 1 s", dist(core.getPosition(), before))

  if not noFall then
    ---------------------------------------------------------------- gravity
    log("== gravity (the ship drops a little)")
    core.setGravity(-0.1)  -- falls upwards gently, so nothing hits the ground
    sleep(1)
    v = core.getVelocity()
    check(v.y > 0.4, "setGravity(-0.1) falls upwards: vy %.2f", v.y)
    core.setGravity(0)
    check(core.getGravity() == 0, "getGravity")
    settle()

    core.setEnabled(false)
    sleep(0.3)
    check(not core.isActive(), "setEnabled(false) stops driving")
    sleep(0.3)
    v = core.getVelocity()
    check(v.y < -1, "powered off the ship falls: vy %.2f", v.y)
    core.setEnabled(true)
    check(settle(5), "back on: brakes to rest")
  end

  ---------------------------------------------------------------- back home
  log("== back home")
  core.setRotation(homeRot.yaw, homeRot.pitch, homeRot.roll)
  core.moveTo(home.x, home.y, home.z)
  check(waitTarget(15) ~= nil, "returns home")
  check(dist(core.getPosition(), home) < 0.2, "home (off by %.2f)", dist(core.getPosition(), home))
  core.stop()
end

local saved = core.getLimits()
local ok, err = pcall(run)
if not ok then
  fail = fail + 1
  log("[FAIL] error: %s", tostring(err))
  pcall(core.stop)
end
core.setLimits({ speed = saved.speed, acceleration = saved.acceleration,
  angularSpeed = saved.angularSpeed, angularAcceleration = saved.angularAcceleration })
log("done: %d passed, %d failed", pass, fail)
logFile.close()
