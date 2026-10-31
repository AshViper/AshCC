-- Altitude hold with every attached thruster (nozzles pointing down).
-- Usage: thruster_hover [targetY]   Needs CC: Sable's `sublevel` API (computer on the ship) for the ship's state.
local thrusters = { peripheral.find("thruster") }
if #thrusters == 0 then error("No thrusters attached", 0) end
if not sublevel or not sublevel.isInPlotGrid() then error("Place the computer on the ship (CC: Sable)", 0) end

local G = 9.8 -- Sable gravity, blocks/s^2
local KP, KD = 1.5, 2.0
local target = tonumber(({...})[1]) or sublevel.getLogicalPose().position.y
local maxTotal = 0
for _, t in ipairs(thrusters) do maxTotal = maxTotal + t.getMaxThrust() end
print(("%d thrusters, max %.0f, target y=%.1f"):format(#thrusters, maxTotal, target))

local function setAll(throttle)
  local calls = {}
  for _, t in ipairs(thrusters) do
    calls[#calls + 1] = function() t.setThrust(throttle) end
  end
  parallel.waitForAll(table.unpack(calls)) -- one tick for all thrusters
end

local ok, err = pcall(function()
  while true do
    local y = sublevel.getLogicalPose().position.y
    local vy = sublevel.getLinearVelocity().y
    local mass = sublevel.getMass()
    -- desired vertical acceleration -> total force -> throttle
    local accel = KP * (target - y) - KD * vy
    local force = mass * (G + accel)
    local throttle = math.max(0, math.min(1, force / maxTotal))
    setAll(throttle)
    term.setCursorPos(1, 3)
    term.clearLine()
    term.write(("y=%.2f vy=%.2f throttle=%.2f"):format(y, vy, throttle))
    sleep(0.05)
  end
end)
setAll(0)
if not ok and err ~= "Terminated" then error(err, 0) end
