-- Event-driven example: report ships and players entering / leaving range.
local radar = peripheral.find("radar") or error("No radar attached", 0)

radar.setWatch(true, { interval = 10, entities = true, subLevels = true, filter = { player = true } })
print("Watching, range " .. radar.getRange())

local names = {}
while true do
  local ev, side, a, b = os.pullEvent()
  if ev == "radar_contact" then
    local c = a
    names[c.id] = c.name or c.id:sub(1, 8)
    print(("+ %s %s  %.0fm  az %.0f"):format(c.kind, names[c.id], c.distance, c.azimuth))
    if c.kind == "sublevel" then
      local aim = radar.intercept(c.id, 60) -- e.g. a 60 blocks/s projectile
      if aim then print(("  lead: yaw %.1f pitch %.1f  t=%.2fs"):format(aim.yaw, aim.pitch, aim.time)) end
    end
  elseif ev == "radar_lost" then
    print(("- %s %s"):format(b, names[a] or a:sub(1, 8)))
    names[a] = nil
  end
end
