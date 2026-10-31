-- Head-aimed laser: every laser on the computer follows where the tracked player looks.
-- Needs a link hub with a head tracker registered (and lasers on the same ship as the hub, or on the ground).
--   head_aim          aim with the look direction (the beam is parallel to your view)
--   head_aim point    aim at the block / entity you are looking at (converges on the target)
-- Hold redstone on the computer's back to fire.
local mode = ...

local tracker = peripheral.find("head_tracker")
if not tracker then error("No head_tracker. Bind a head tracker and use it on a link hub", 0) end
local lasers = { peripheral.find("laser") }
if #lasers == 0 then error("No laser", 0) end

print(("Tracking %s with %d laser(s), mode %s"):format(tracker.getPlayer(), #lasers, mode or "direction"))
while true do
  if tracker.isOnline() then
    local hit = mode == "point" and tracker.getLookTarget(512) or nil
    for _, laser in ipairs(lasers) do
      if hit and hit.type ~= "none" then
        laser.aimAt(hit.x, hit.y, hit.z)
      else
        laser.setAim(tracker.getLocalAngles())
      end
      if redstone.getInput("back") then laser.fire(1) end
    end
  end
  sleep(0.05)
end
