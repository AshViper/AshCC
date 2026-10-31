-- Quad flap bearing test program (peripheral type "quad_flap_bearing").
--   quad_flap_test [name] [--keep]   automatic test (every quad bearing, also through a link hub)
--   quad_flap_test manual [name]     tab: flap, left/right: angle, up/down: speed, space: 0, a/d: (dis)assemble all, q: quit
--   quad_flap_test wave <pattern> [amp] [period]
--        pattern: pair1 | pair2 = one opposite pair deflects together (pitch / yaw style)
--                 roll          = each pair deflects in opposite directions
--                 each          = the four flaps one after another
-- A positive angle turns a flap right-handed about the positive axis of its face, so opposite flaps with the
-- same angle turn the same way (together), and opposite signs twist the body (roll).
local args = { ... }
local LOG = "quad_flap_test.log"

local function now() return os.epoch("utc") / 1000 end

local logFile
local function log(fmt, ...)
  local line = string.format(fmt, ...)
  print(line)
  if logFile then logFile.writeLine(line) end
end

local function findQuads(only)
  local list = {}
  for _, name in ipairs(peripheral.getNames()) do
    if peripheral.hasType(name, "quad_flap_bearing") and (not only or name == only) then
      list[#list + 1] = { name = name, p = peripheral.wrap(name) }
    end
  end
  table.sort(list, function(a, b) return a.name < b.name end)
  if #list == 0 then error("No quad flap bearing found" .. (only and (": " .. only) or ""), 0) end
  return list
end

local OPPOSITE = { north = "south", south = "north", east = "west", west = "east", up = "down", down = "up" }

-- The two opposite pairs, e.g. { {"north","south"}, {"west","east"} }
local function pairsOf(p)
  local seen, out = {}, {}
  for _, s in ipairs(p.getSides()) do
    if not seen[s] then
      seen[s], seen[OPPOSITE[s]] = true, true
      out[#out + 1] = { s, OPPOSITE[s] }
    end
  end
  return out
end

local function waitAll(p, timeout)
  local t0 = now()
  while now() - t0 < timeout do
    if p.isAtTarget() then return now() - t0 end
    sleep(0.05)
  end
  return nil
end

---------------------------------------------------------------- auto
local function autoTest(q, keep)
  local p, pass, fail = q.p, 0, 0
  local function check(ok, fmt, ...)
    if ok then pass = pass + 1 else fail = fail + 1 end
    log("  [%s] " .. fmt, ok and "PASS" or "FAIL", ...)
  end
  log("== %s", q.name)
  local sides = p.getSides()
  local f = p.getFacing()
  log("  sides %s, body axis %.2f %.2f %.2f", table.concat(sides, ","), f.x, f.y, f.z)
  check(#sides == 4, "four flap sides")

  local n, errors = p.assemble()
  for side, why in pairs(errors) do log("  %s: %s", side, why) end
  check(n == 4, "assembled %d/4", n)
  local assembled = p.isAssembled()

  local speed = math.min(180, p.getMaxSpeed())
  p.setSpeed(speed)
  check(math.abs(p.getSpeed() - speed) < 1e-6, "speed %.0f deg/s", speed)

  -- Each flap gets a different angle; all must arrive independently
  local want, i = {}, 0
  for _, s in ipairs(sides) do
    i = i + 1
    if assembled[s] then want[s] = ({ 20, -20, 35, -10 })[i] end
  end
  p.setAngles(want)
  local took = waitAll(p, 60 / speed + 2)
  check(took ~= nil, "all flaps reached their targets in %s s", took and string.format("%.2f", took) or "timeout")
  local angles = p.getAngle()
  for s, a in pairs(want) do
    check(math.abs(angles[s] - a) < 0.5, "%-5s %6.1f (target %6.1f)", s, angles[s], a)
  end

  -- "all" sets every flap
  p.setAngle("all", 15)
  waitAll(p, 60 / speed + 2)
  local same = true
  for s in pairs(want) do same = same and math.abs(p.getAngle(s) - 15) < 0.5 end
  check(same, "setAngle(\"all\", 15)")

  -- Moving one flap leaves the others alone
  local first = sides[1]
  p.setAngle(first, -25)
  waitAll(p, 60 / speed + 2)
  local others = true
  for s in pairs(want) do if s ~= first then others = others and math.abs(p.getAngle(s) - 15) < 0.5 end end
  check(math.abs(p.getAngle(first) + 25) < 0.5 and others, "only %s moved", first)

  -- Not a flap side
  local axisName
  for _, s in ipairs({ "up", "down", "north", "south", "east", "west" }) do
    local isSide = false
    for _, t in ipairs(sides) do isSide = isSide or t == s end
    if not isSide then axisName = s break end
  end
  check(not pcall(p.setAngle, axisName, 5), "%s (body axis) is rejected", axisName)

  if keep then
    p.setAngle("all", 0)
    waitAll(p, 60 / speed + 2)
  else
    if not p.disassemble() then
      local t0 = now()
      repeat sleep(0.05) local any = false for _, v in pairs(p.isAssembled()) do any = any or v end until not any or now() - t0 > 360 / speed + 3
    end
    local any = false
    for _, v in pairs(p.isAssembled()) do any = any or v end
    check(not any, "disassembled (after returning to 0)")
  end
  return pass, fail
end

---------------------------------------------------------------- manual
local function manual(list)
  local qi, si, step = 1, 1, 5
  local function draw()
    local q = list[qi]
    local sides = q.p.getSides()
    local angles, targets, asm = q.p.getAngle(), q.p.getTargetAngle(), q.p.isAssembled()
    term.clear()
    term.setCursorPos(1, 1)
    print(("%s  speed %.0f deg/s  step %d"):format(q.name, q.p.getSpeed(), step))
    for i, s in ipairs(sides) do
      print(("%s %-5s %s  %7.2f -> %7.2f"):format(i == si and ">" or " ", s, asm[s] and "on " or "off", angles[s], targets[s]))
    end
    print("")
    print("tab: flap  left/right: angle  up/down: speed")
    print("1-9: step  space: 0  a/d: (dis)assemble all")
    print("n: next bearing  q: quit")
  end
  local timer = os.startTimer(0.2)
  draw()
  while true do
    local ev, key = os.pullEvent()
    local p = list[qi].p
    local side = p.getSides()[si]
    if ev == "timer" and key == timer then timer = os.startTimer(0.2)
    elseif ev == "key" then
      if key == keys.q then break
      elseif key == keys.tab then si = si % 4 + 1
      elseif key == keys.left then p.setAngle(side, p.getTargetAngle(side) - step)
      elseif key == keys.right then p.setAngle(side, p.getTargetAngle(side) + step)
      elseif key == keys.up then p.setSpeed(p.getSpeed() * 1.5)
      elseif key == keys.down then p.setSpeed(p.getSpeed() / 1.5)
      elseif key == keys.space then p.setAngle(side, 0)
      elseif key == keys.a then p.assemble()
      elseif key == keys.d then p.disassemble()
      elseif key == keys.n then qi = qi % #list + 1 end
    elseif ev == "char" and tonumber(key) then step = tonumber(key) end
    draw()
  end
  term.clear()
  term.setCursorPos(1, 1)
end

---------------------------------------------------------------- wave
local function wave(list, pattern, amp, period)
  for _, q in ipairs(list) do q.p.assemble(); q.p.setSpeed(q.p.getMaxSpeed()) end
  print(("Pattern %s, +-%.0f deg, period %.1f s. Ctrl+T to stop."):format(pattern, amp, period))
  local t0 = now()
  local ok, err = pcall(function()
    while true do
      local t = now() - t0
      local a = amp * math.sin(2 * math.pi * t / period)
      local calls = {}
      for _, q in ipairs(list) do
        local pr, sides, want = pairsOf(q.p), q.p.getSides(), {}
        for _, s in ipairs(sides) do want[s] = 0 end
        if pattern == "pair1" or pattern == "pair2" then
          local pair = pr[pattern == "pair1" and 1 or 2]
          want[pair[1]], want[pair[2]] = a, a
        elseif pattern == "roll" then
          for _, pair in ipairs(pr) do want[pair[1]], want[pair[2]] = a, -a end
        else -- each: one flap at a time, a quarter period each
          local k = math.floor(t / (period / 4)) % 4 + 1
          want[sides[k]] = amp * math.abs(math.sin(4 * math.pi * t / period))
        end
        calls[#calls + 1] = function() q.p.setAngles(want) end
      end
      parallel.waitForAll(table.unpack(calls))
      term.setCursorPos(1, 3)
      term.clearLine()
      term.write(("command %6.1f"):format(a))
      sleep(0.05)
    end
  end)
  for _, q in ipairs(list) do pcall(q.p.setAngle, "all", 0) end
  print("")
  if not ok and err ~= "Terminated" then error(err, 0) end
end

---------------------------------------------------------------- main
local mode = args[1]
if mode == "manual" then
  manual(findQuads(args[2]))
elseif mode == "wave" then
  local pattern = args[2] or "pair1"
  if not ({ pair1 = 1, pair2 = 1, roll = 1, each = 1 })[pattern] then error("pattern: pair1 | pair2 | roll | each", 0) end
  wave(findQuads(), pattern, tonumber(args[3]) or 20, tonumber(args[4]) or 2)
elseif mode == "help" or mode == "-h" then
  print("Usage: quad_flap_test [name] [--keep] | manual [name] | wave <pair1|pair2|roll|each> [amp] [period]")
else
  local only, keep = nil, false
  for _, a in ipairs(args) do
    if a == "--keep" then keep = true elseif a ~= "auto" then only = only or a end
  end
  local list = findQuads(only)
  logFile = fs.open(LOG, "w")
  log("Quad flap bearing test, %d bearing(s)", #list)
  local pass, fail = 0, 0
  for _, q in ipairs(list) do
    local ok, a, b = pcall(autoTest, q, keep)
    if ok then pass, fail = pass + a, fail + b
    else fail = fail + 1; log("  [FAIL] error: %s", tostring(a)) end
  end
  log("== %d passed, %d failed", pass, fail)
  logFile.close()
  print("Log written to " .. LOG)
end
