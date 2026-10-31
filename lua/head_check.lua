-- Head tracker diagnostics: shows each step live so you can see where it stops.
-- Run it, close the computer screen, look around, then open the screen again.
local function step(label, f, ...)
  local t0 = os.epoch("utc")
  local r = table.pack(pcall(f, ...))
  local ms = os.epoch("utc") - t0
  if not r[1] then return ("%s: ERROR %s (%dms)"):format(label, tostring(r[2]), ms) end
  local out = {}
  for i = 2, r.n do
    local v = r[i]
    out[#out + 1] = type(v) == "number" and ("%.1f"):format(v) or tostring(v)
  end
  return ("%s: %s (%dms)"):format(label, table.concat(out, ", "), ms)
end

local names = peripheral.getNames()
local tracker = peripheral.find("head_tracker")
local laser = peripheral.find("laser")
local log = fs.open("head_check.log", "w")

for i = 1, 600 do
  local lines = {
    "peripherals: " .. table.concat(names, " "),
    "tracker: " .. (tracker and peripheral.getName(tracker) or "nil"),
    "laser: " .. (laser and peripheral.getName(laser) or "nil"),
  }
  if tracker then
    lines[#lines + 1] = step("isOnline", tracker.isOnline)
    lines[#lines + 1] = step("getAngles", tracker.getAngles)
    lines[#lines + 1] = step("getLocalAngles", tracker.getLocalAngles)
  end
  if laser and tracker then
    local ok, y, p = pcall(tracker.getLocalAngles)
    if ok then lines[#lines + 1] = step("laser.setAim", laser.setAim, y, p) end
    lines[#lines + 1] = step("laser.getAim", laser.getAim)
    lines[#lines + 1] = step("laser.getTargetAim", laser.getTargetAim)
  end
  term.clear()
  term.setCursorPos(1, 1)
  print(("head_check %d/600"):format(i))
  for _, l in ipairs(lines) do print(l) end
  log.writeLine(("[%d] %s"):format(i, table.concat(lines, " | ")))
  log.flush()
  sleep(0.1)
end
log.close()
