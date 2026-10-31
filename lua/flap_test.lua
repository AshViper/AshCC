-- Flap bearing test program.
--   flap_test                 automatic test of every flap bearing (works through a link hub too)
--   flap_test auto [name] [--keep]   same, one bearing only; --keep leaves it assembled
--   flap_test manual [name]   drive it with the keyboard
--   flap_test wave [amp] [period]   swing all flaps (sine), e.g. to watch a ship react
local args = { ... }
local mode = args[1] or "auto"
local LOG = "flap_test.log"

local function now() return os.epoch("utc") / 1000 end

local function findFlaps(only)
  local list = {}
  for _, name in ipairs(peripheral.getNames()) do
    if peripheral.hasType(name, "flap_bearing") and (not only or name == only) then
      list[#list + 1] = { name = name, p = peripheral.wrap(name) }
    end
  end
  table.sort(list, function(a, b) return a.name < b.name end)
  return list
end

local logFile
local function log(fmt, ...)
  local line = string.format(fmt, ...)
  print(line)
  if logFile then logFile.writeLine(line) end
end

local function ensureAssembled(f)
  if f.p.isAssembled() then return true end
  local ok, why = f.p.assemble()
  if not ok then log("  assemble failed: %s", tostring(why)) end
  return ok
end

-- Wait until the bearing reports it reached the target (or timeout). Returns elapsed seconds or nil.
local function waitTarget(p, timeout)
  local t0 = now()
  while now() - t0 < timeout do
    if p.isAtTarget() then return now() - t0 end
    sleep(0.05)
  end
  return nil
end

---------------------------------------------------------------- auto
local function autoTest(f, keep)
  local p, pass, fail = f.p, 0, 0
  local function check(ok, fmt, ...)
    if ok then pass = pass + 1 else fail = fail + 1 end
    log("  [%s] " .. fmt, ok and "PASS" or "FAIL", ...)
  end

  log("== %s", f.name)
  local ax = p.getAxis()
  log("  axis (world) %.2f %.2f %.2f, max angle %.0f, max speed %.0f deg/s", ax.x, ax.y, ax.z, p.getMaxAngle(), p.getMaxSpeed())
  check(ensureAssembled(f), "assemble")
  if not p.isAssembled() then return pass, fail end

  -- Speed setting and clamping
  check(p.setSpeed(1e9) <= p.getMaxSpeed(), "speed is clamped to %.0f", p.getMaxSpeed())
  local speed = math.min(90, p.getMaxSpeed())
  check(p.setSpeed(speed) == speed, "speed set to %.0f deg/s", speed)

  -- Start from 0
  p.setAngle(0)
  waitTarget(p, 360 / speed + 2)

  -- Sweep: each move is timed and compared with distance / speed
  local max = p.getMaxAngle()
  local targets = { 30, -30, math.min(90, max), -math.min(90, max), 0 }
  for _, target in ipairs(targets) do
    local from = p.getAngle()
    local set = p.setAngle(target)
    local expected = math.abs(set - from) / speed
    local took = waitTarget(p, expected * 2 + 2)
    local final = p.getAngle()
    if took then
      check(math.abs(final - target) < 0.5, "%6.1f -> %6.1f  final %6.2f  %.2fs (expected %.2fs)", from, target, final, took, expected)
      if expected > 0.5 then
        local measured = math.abs(set - from) / took
        check(measured > speed * 0.7 and measured < speed * 1.3, "   measured speed %.0f deg/s", measured)
      end
    else
      check(false, "%6.1f -> %6.1f  timed out at %6.2f", from, target, final)
    end
  end

  -- Out-of-range targets
  if max >= 90 then check(p.setAngle(270) == -90, "270 wraps to -90") end
  if max < 180 then check(p.setAngle(max + 5) == max, "%.0f is clamped to %.0f", max + 5, max) end
  p.setAngle(0)
  waitTarget(p, 360 / speed + 2)

  -- Fast move
  local fast = p.setSpeed(p.getMaxSpeed())
  p.setAngle(45)
  local took = waitTarget(p, 45 / fast + 2)
  check(took ~= nil, "fast move to 45 at %.0f deg/s took %s s", fast, took and string.format("%.2f", took) or "timeout")
  p.setSpeed(speed)

  -- Disassemble (returns to 0 first)
  if not keep then
    local now0 = p.disassemble()
    if not now0 then
      local t0 = now()
      while p.isAssembled() and now() - t0 < 360 / speed + 3 do sleep(0.05) end
    end
    check(not p.isAssembled(), "disassembled at 0 deg")
  else
    p.setAngle(0)
    waitTarget(p, 360 / speed + 2)
  end
  return pass, fail
end

---------------------------------------------------------------- manual
local function manual(flaps)
  local i, step = 1, 5
  local function draw()
    local f = flaps[i]
    term.clear()
    term.setCursorPos(1, 1)
    print(("Flap %d/%d: %s"):format(i, #flaps, f.name))
    print(("assembled: %s"):format(tostring(f.p.isAssembled())))
    print(("angle  %7.2f   target %7.2f  %s"):format(f.p.getAngle(), f.p.getTargetAngle(), f.p.isAtTarget() and "at target" or "moving"))
    print(("speed  %7.0f deg/s   step %d deg"):format(f.p.getSpeed(), step))
    print("")
    print("left/right: angle -/+   up/down: speed")
    print("1-9: step   space: 0 deg   a: assemble")
    print("d: disassemble   tab: next flap   q: quit")
  end
  local timer = os.startTimer(0.2)
  draw()
  while true do
    local ev, key = os.pullEvent()
    local p = flaps[i].p
    if ev == "timer" and key == timer then
      timer = os.startTimer(0.2)
    elseif ev == "key" then
      if key == keys.q then break
      elseif key == keys.left then p.setAngle(p.getTargetAngle() - step)
      elseif key == keys.right then p.setAngle(p.getTargetAngle() + step)
      elseif key == keys.up then p.setSpeed(p.getSpeed() * 1.5)
      elseif key == keys.down then p.setSpeed(p.getSpeed() / 1.5)
      elseif key == keys.space then p.setAngle(0)
      elseif key == keys.a then
        local ok, why = p.assemble()
        if not ok then flaps[i].msg = why end
      elseif key == keys.d then p.disassemble()
      elseif key == keys.tab then i = i % #flaps + 1
      end
    elseif ev == "char" and tonumber(key) then
      step = tonumber(key)
    end
    draw()
    if flaps[i].msg then print("assemble failed: " .. flaps[i].msg); flaps[i].msg = nil end
  end
  term.clear()
  term.setCursorPos(1, 1)
end

---------------------------------------------------------------- wave
local function wave(flaps, amp, period)
  for _, f in ipairs(flaps) do
    if ensureAssembled(f) then f.p.setSpeed(f.p.getMaxSpeed()) end
  end
  print(("Waving %d flaps: +-%.0f deg, period %.1f s. Ctrl+T to stop."):format(#flaps, amp, period))
  local t0 = now()
  local ok, err = pcall(function()
    while true do
      local a = amp * math.sin(2 * math.pi * (now() - t0) / period)
      local calls = {}
      for _, f in ipairs(flaps) do
        calls[#calls + 1] = function() if f.p.isAssembled() then f.p.setAngle(a) end end
      end
      parallel.waitForAll(table.unpack(calls)) -- same tick for all flaps
      term.setCursorPos(1, 2)
      term.clearLine()
      term.write(("command %6.1f  actual %6.1f"):format(a, flaps[1].p.getAngle()))
      sleep(0.05)
    end
  end)
  for _, f in ipairs(flaps) do pcall(f.p.setAngle, 0) end
  print("")
  if not ok and err ~= "Terminated" then error(err, 0) end
end

---------------------------------------------------------------- main
if mode == "manual" then
  local flaps = findFlaps(args[2])
  if #flaps == 0 then error("No flap bearing found", 0) end
  manual(flaps)
elseif mode == "wave" then
  local flaps = findFlaps()
  if #flaps == 0 then error("No flap bearing found", 0) end
  wave(flaps, tonumber(args[2]) or 20, tonumber(args[3]) or 2)
elseif mode == "auto" or mode == "--keep" or (mode ~= "help" and not mode:match("^%-")) then
  -- auto [name] [--keep]; the first word may also be a bearing name
  local only, keep = nil, false
  for i = 1, #args do
    if args[i] == "--keep" then keep = true
    elseif not (i == 1 and args[i] == "auto") then only = only or args[i] end
  end
  local flaps = findFlaps(only)
  if #flaps == 0 then error("No flap bearing found" .. (only and (": " .. only) or ""), 0) end
  logFile = fs.open(LOG, "w")
  log("Flap bearing test, %d bearing(s)", #flaps)
  local pass, fail = 0, 0
  for _, f in ipairs(flaps) do
    local ok, p, q = pcall(autoTest, f, keep)
    if ok then pass, fail = pass + p, fail + q
    else fail = fail + 1; log("  [FAIL] error: %s", tostring(p)) end
  end
  log("== %d passed, %d failed", pass, fail)
  logFile.close()
  print("Log written to " .. LOG)
else
  print("Usage: flap_test [auto [name] [--keep] | manual [name] | wave [amp] [period]]")
end
