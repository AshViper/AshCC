-- Flight instruments: attitude, rates, speed, climb and g-load (monitor if attached).
--   imu_view [ticks]   update interval in ticks (default 2)
-- Every computer has the global "imu" API (AshCC), so no block and no CC: Sable are needed. q quits.
local args = { ... }
local interval = tonumber(args[1]) or 2

if not imu then error("No imu API (is AshCC installed?)", 0) end
local out = peripheral.find("monitor") or term.current()
if out.setTextScale then out.setTextScale(0.5) end

local peakG, peakAt = 1, 0

local function bar(value, range, width)
  -- A centred bar: |----+====      |
  local half = math.floor(width / 2)
  local n = math.max(-half, math.min(half, math.floor(value / range * half + 0.5)))
  local s = {}
  for i = -half, half do
    if i == 0 then s[#s + 1] = "+"
    elseif (n > 0 and i > 0 and i <= n) or (n < 0 and i < 0 and i >= n) then s[#s + 1] = "="
    else s[#s + 1] = "-" end
  end
  return table.concat(s)
end

local function draw(t)
  local w = out.getSize()
  local g = math.sqrt(t.gForce.x ^ 2 + t.gForce.y ^ 2 + t.gForce.z ^ 2)
  if g > peakG or os.clock() - peakAt > 5 then peakG, peakAt = g, os.clock() end
  local lines = {
    { ("IMU %s   tick %d"):format(t.onShip and "on ship" or "on ground", t.tick), colors.yellow },
    { ("heading %6.1f   yaw rate %+7.1f /s"):format((t.yaw + 360) % 360, t.yawRate), colors.white },
    { ("pitch   %+6.1f   %s"):format(t.pitch, bar(t.pitch, 45, w - 18)), colors.lightBlue },
    { ("roll    %+6.1f   %s"):format(t.roll, bar(t.roll, 45, w - 18)), colors.lightBlue },
    { ("rates p %+6.1f  r %+6.1f /s"):format(t.pitchRate, t.rollRate), colors.lightGray },
    { ("speed   %6.2f b/s  fwd %+6.2f"):format(t.speed, t.localVelocity.z), colors.white },
    { ("climb   %+6.2f b/s  alt %7.1f"):format(t.velocity.y, t.position.y), colors.white },
    { ("load    %5.2f g  (peak %.2f)  side %+5.2f"):format(g, peakG, t.gForce.x), g > 2 and colors.red or colors.lime },
    { "q quit", colors.gray },
  }
  out.setBackgroundColor(colors.black)
  out.clear()
  for i, l in ipairs(lines) do
    out.setCursorPos(1, i)
    out.setTextColor(l[2])
    out.write(l[1]:sub(1, w))
  end
end

imu.setStream(interval)
draw(imu.getAll())
parallel.waitForAny(function()
  while true do
    local _, _, t = os.pullEvent("imu")
    draw(t)
  end
end, function()
  repeat local _, k = os.pullEvent("key") until k == keys.q
end)
imu.setStream(0)
out.clear()
out.setCursorPos(1, 1)
