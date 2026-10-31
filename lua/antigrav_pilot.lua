-- Anti-gravity core pilot.
--   antigrav_pilot                 status display (updates until a key is pressed)
--   antigrav_pilot fly [speed]     keyboard flight: W/S forward/back, A/D strafe, Space/Shift up/down,
--                                  Q/E turn, R level out, X stop, Backspace quit
--   antigrav_pilot goto x y z      fly the core to a world point and wait until it arrives
--   antigrav_pilot up n            climb n blocks (negative = down)
--   antigrav_pilot face yaw [pitch] [roll]
--   antigrav_pilot stop | hold | free | on | off
--   antigrav_pilot gravity s       0 = weightless, 1 = normal
local core = peripheral.find("antigravity")
if not core then error("no antigravity core attached", 0) end

local args = { ... }
local cmd = args[1]

local function fmt(v) return ("%7.2f"):format(v) end

local function status()
  local s = core.getState()
  term.clear()
  term.setCursorPos(1, 1)
  print(("on %s  active %s  redstone %s  g %.2f"):format(
    tostring(s.enabled), tostring(s.active), tostring(s.redstone), s.gravity))
  print(("linear  %-14s angular %s"):format(s.linear.mode, s.angular.mode))
  if not s.position then
    print("not on a ship")
    return
  end
  local p, v, r, w = s.position, s.velocity, s.rotation, s.angularVelocity
  print("pos   " .. fmt(p.x) .. fmt(p.y) .. fmt(p.z))
  print("vel   " .. fmt(v.x) .. fmt(v.y) .. fmt(v.z) .. "   fwd " .. fmt(v["local"].forward))
  print("rot   yaw" .. fmt(r.yaw) .. " pitch" .. fmt(r.pitch) .. " roll" .. fmt(r.roll))
  print("rate  yaw" .. fmt(w.yawRate) .. " pitch" .. fmt(w.pitchRate) .. " roll" .. fmt(w.rollRate))
  print(("mass %.0f  at target %s"):format(s.mass, tostring(s.atTarget)))
  if s.linear.target then
    local t = s.linear.target
    print("goto  " .. fmt(t.x) .. fmt(t.y) .. fmt(t.z))
  end
end

local function waitArrived()
  while not core.isAtTarget() do
    local ev = os.pullEvent()  -- antigravity_arrived wakes us; the loop re-checks
    if ev == "key" then return false end
  end
  return true
end

local function fly(speed)
  speed = speed or 10
  local turnRate = 45
  local held = {}
  local action  -- "stop" / "level", done by the control loop

  -- Peripheral calls that wait for the server tick throw away every other event that arrives meanwhile,
  -- so the keyboard is read in its own coroutine that never calls the core.
  local function keyboard()
    while true do
      local ev, a = os.pullEvent()
      if ev == "key" then
        held[a] = true
        if a == keys.backspace then return end
        if a == keys.x then action = "stop" end
        if a == keys.r then action = "level" end
      elseif ev == "key_up" then
        held[a] = nil
      end
    end
  end

  local function control()
    local turning = false
    core.stop()
    core.level()
    while true do
      if action == "stop" then
        core.stop()
        turning = false
      elseif action == "level" then
        core.level()
      end
      action = nil
      local fwd = (held[keys.w] and 1 or 0) - (held[keys.s] and 1 or 0)
      local right = (held[keys.d] and 1 or 0) - (held[keys.a] and 1 or 0)
      local up = (held[keys.space] and 1 or 0) - (held[keys.leftShift] and 1 or 0)
      local turn = (held[keys.e] and 1 or 0) - (held[keys.q] and 1 or 0)
      core.setLocalVelocity(right * speed, up * speed, fwd * speed)
      if turn ~= 0 then
        core.setAngularVelocity(turn * turnRate, 0, 0)
        turning = true
      elseif turning then
        -- Brake the turn first; locking the heading straight away would swing past it and back
        core.setAngularVelocity(0, 0, 0)
        if math.abs(core.getAngularVelocity().yawRate) < 2 then
          core.level()  -- hold the new heading, wings level
          turning = false
        end
      end
      status()
      print("WASD move  Space/Shift up/down  Q/E turn  R level  X stop  Backspace quit")
      sleep(0.1)
    end
  end

  parallel.waitForAny(keyboard, control)
  core.stop()
end

if cmd == nil then
  local timer = os.startTimer(0)
  while true do
    local ev, a = os.pullEvent()
    if ev == "key" then break end
    if ev == "timer" and a == timer then
      status()
      print("press any key to exit")
      timer = os.startTimer(0.25)
    end
  end
elseif cmd == "fly" then
  fly(tonumber(args[2]))
elseif cmd == "goto" then
  core.moveTo(tonumber(args[2]), tonumber(args[3]), tonumber(args[4]))
  print(waitArrived() and "arrived" or "still moving")
elseif cmd == "up" then
  core.move(0, tonumber(args[2]) or 1, 0)
  print(waitArrived() and "arrived" or "still moving")
elseif cmd == "face" then
  core.setRotation(tonumber(args[2]) or 0, tonumber(args[3]) or 0, tonumber(args[4]) or 0)
  print(waitArrived() and "done" or "still turning")
elseif cmd == "stop" then core.stop()
elseif cmd == "hold" then core.hold()
elseif cmd == "free" then core.release()
elseif cmd == "on" then core.setEnabled(true)
elseif cmd == "off" then core.setEnabled(false)
elseif cmd == "gravity" then core.setGravity(tonumber(args[2]) or 0)
else
  print("usage: antigrav_pilot [fly [speed] | goto x y z | up n | face yaw [pitch] [roll] | stop | hold | free | on | off | gravity s]")
end
