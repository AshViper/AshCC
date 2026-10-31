-- Seeker test program (peripheral type "seeker").
--   seeker_test [name]                  automatic test: overloads, result rules, narrowing the cone / distance
--   seeker_test track [angle] [dist] [name]   live view: target on a crosshair (terminal, or a monitor if attached)
-- Put at least one Sable ship somewhere in front of the seeker for the automatic test.
local args = { ... }
local LOG = "seeker_test.log"

local logFile
local function log(fmt, ...)
  local line = string.format(fmt, ...)
  print(line)
  if logFile then logFile.writeLine(line) end
end

local function findSeekers(only)
  local list = {}
  for _, name in ipairs(peripheral.getNames()) do
    if peripheral.hasType(name, "seeker") and (not only or name == only) then
      list[#list + 1] = { name = name, p = peripheral.wrap(name) }
    end
  end
  table.sort(list, function(a, b) return a.name < b.name end)
  if #list == 0 then error("No seeker found" .. (only and (": " .. only) or ""), 0) end
  return list
end

local function centerDistance(t)
  return t.centerDistance or t.distance
end

local function describe(t)
  if not t then return "nil" end
  return ("%s %s  off %.2f  dist %.1f  az %.1f el %.1f"):format(t.name or "?", t.id:sub(1, 8), t.offAxis, centerDistance(t), t.azimuth, t.elevation)
end

---------------------------------------------------------------- auto
local function autoTest(s)
  local p, pass, fail = s.p, 0, 0
  local function check(ok, fmt, ...)
    if ok then pass = pass + 1 else fail = fail + 1 end
    log("  [%s] " .. fmt, ok and "PASS" or "FAIL", ...)
  end
  log("== %s", s.name)

  -- Overloads
  local ok0, t0 = pcall(p.seek)
  local ok1 = pcall(p.seek, 30)
  local ok2 = pcall(p.seek, 30, 100)
  check(ok0 and ok1 and ok2, "seek(), seek(angle), seek(angle, distance) all accepted")
  check(not pcall(p.seek, -1), "negative angle rejected")
  check(not pcall(p.seek, 181), "angle over 180 rejected")
  check(not pcall(p.seek, 10, -5), "negative distance rejected")
  check(p.seek(0, 0) == nil, "seek(0, 0) finds nothing")
  log("  seek() = %s", describe(t0))

  -- The widest search: every ship in a big sphere, closest to the line of sight
  local wide = p.seek(180, 1e6)
  if not wide then
    log("  no ship found at all (180 deg, 1e6 blocks): place a Sable ship and run again")
    return pass, fail
  end
  log("  seek(180, 1e6) = %s", describe(wide))
  check(type(wide.id) == "string" and wide.kind == "sublevel", "result is a sub-level table")
  for _, k in ipairs({ "x", "y", "z", "vx", "vy", "vz", "offAxis", "azimuth", "elevation", "distance" }) do
    if type(wide[k]) ~= "number" then check(false, "field %s missing", k) end
  end
  check(wide.offAxis >= 0 and wide.offAxis <= 180, "offAxis within 0..180")

  -- offAxis agrees with the local coordinates (z = forward)
  local l = wide["local"]
  local len = math.sqrt(l.x ^ 2 + l.y ^ 2 + l.z ^ 2)
  local fromLocal = len > 0 and math.deg(math.acos(math.max(-1, math.min(1, l.z / len)))) or 0
  check(math.abs(fromLocal - wide.offAxis) < 0.5, "offAxis %.2f matches local coordinates (%.2f)", wide.offAxis, fromLocal)

  -- Narrowing the cone around the found ship: just above its offAxis finds it, just below does not
  local o = wide.offAxis
  local above = p.seek(math.min(180, o + 0.5), 1e6)
  check(above ~= nil and above.offAxis <= o + 1e-6, "seek(%.2f) still finds it (or one closer to the centre)", o + 0.5)
  if o > 0.6 then
    local below = p.seek(o - 0.5, 1e6)
    check(below == nil or below.id ~= wide.id, "seek(%.2f) excludes it", o - 0.5)
  end

  -- Distance limit around the found ship
  local d = centerDistance(wide)
  local near = p.seek(180, d + 1)
  check(near ~= nil and centerDistance(near) <= d + 1 + 1e-6, "seek(180, %.1f) finds a ship within that distance", d + 1)
  if d > 1.5 then
    local closer = p.seek(180, d - 1)
    check(closer == nil or closer.id ~= wide.id, "seek(180, %.1f) excludes it", d - 1)
  end

  -- Every result obeys its own limits
  local okAll = true
  for _, angle in ipairs({ 5, 15, 30, 60, 90, 180 }) do
    for _, dist in ipairs({ 16, 64, 256, 1024 }) do
      local t = p.seek(angle, dist)
      if t and (t.offAxis > angle + 1e-6 or centerDistance(t) > dist + 1e-6) then
        okAll = false
        log("    seek(%d, %d) broke its limits: %s", angle, dist, describe(t))
      end
    end
  end
  check(okAll, "results respect angle and distance for 24 combinations")

  -- A wider cone never returns something further off-axis than a narrower one
  local prev, mono = nil, true
  for _, angle in ipairs({ 5, 15, 30, 60, 90, 180 }) do
    local t = p.seek(angle, 1e6)
    if prev and t and t.offAxis > prev.offAxis + 1e-6 then mono = false end
    prev = t or prev
  end
  check(mono, "widening the cone keeps the best (smallest offAxis) target")
  return pass, fail
end

---------------------------------------------------------------- track
local function track(s, angle, dist)
  local out = peripheral.find("monitor") or term
  if out.setTextScale then out.setTextScale(0.5) end
  print(("Tracking with %s, cone %.0f deg, %.0f blocks. Ctrl+T to stop."):format(s.name, angle, dist))
  local ok, err = pcall(function()
    while true do
      local t = s.p.seek(angle, dist)
      local w, h = out.getSize()
      local cx, cy = math.floor(w / 2), math.floor(h / 2) + 1
      out.setBackgroundColor(colors.black)
      out.clear()
      out.setTextColor(colors.gray)
      for x = 1, w do out.setCursorPos(x, cy) out.write("-") end
      for y = 2, h do out.setCursorPos(cx, y) out.write("|") end
      out.setCursorPos(1, 1)
      out.setTextColor(colors.white)
      if t then
        -- Screen edge = the cone's edge
        local sx = cx + math.floor(t.azimuth / angle * (w / 2 - 1) + 0.5)
        local sy = cy - math.floor(t.elevation / angle * (h / 2 - 1) + 0.5)
        out.write(("%s off %.1f  %.0fm  v %.1f"):format(t.name or t.id:sub(1, 8), t.offAxis, centerDistance(t),
          math.sqrt(t.vx ^ 2 + t.vy ^ 2 + t.vz ^ 2)))
        if sx >= 1 and sx <= w and sy >= 2 and sy <= h then
          out.setCursorPos(sx, sy)
          out.setTextColor(colors.red)
          out.write("#")
        end
      else
        out.write("no target")
      end
      sleep(0.1)
    end
  end)
  if out ~= term then out.clear() end
  if not ok and err ~= "Terminated" then error(err, 0) end
end

---------------------------------------------------------------- main
if args[1] == "track" then
  local angle = tonumber(args[2]) or 30
  local dist = tonumber(args[3]) or 512
  track(findSeekers(args[4])[1], angle, dist)
elseif args[1] == "help" or args[1] == "-h" then
  print("Usage: seeker_test [name] | track [angle] [dist] [name]")
else
  local list = findSeekers(args[1] ~= "auto" and args[1] or args[2])
  logFile = fs.open(LOG, "w")
  log("Seeker test, %d seeker(s)", #list)
  local pass, fail = 0, 0
  for _, s in ipairs(list) do
    local ok, a, b = pcall(autoTest, s)
    if ok then pass, fail = pass + a, fail + b
    else fail = fail + 1; log("  [FAIL] error: %s", tostring(a)) end
  end
  log("== %d passed, %d failed", pass, fail)
  logFile.close()
  print("Log written to " .. LOG)
end
