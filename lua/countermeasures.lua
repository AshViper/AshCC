-- Countermeasures: every connected flare / chaff launcher fires when a non-friendly ship closes in.
--   countermeasures [range]
--     range  danger radius in blocks (default CFG.range)
-- Keys: f flares now, c chaff now, a auto on/off, q quit.
-- Friendly = sends the same IFF code as our own ship (needs an IFF transponder on both), or is in CFG.friendCodes.
local args = { ... }

local CFG = {
  range = 96,           -- a ship this close counts as a threat
  closing = 8,          -- ...when it approaches faster than this (blocks/s)
  salvo = 2,            -- decoys per launcher per salvo
  interval = 3,         -- seconds between automatic salvos
  friendCodes = {},     -- e.g. { BLUE = true }
}
if tonumber(args[1]) then CFG.range = tonumber(args[1]) end

local radar = peripheral.find("radar")
if not radar then error("No radar found", 0) end
local launchers = { peripheral.find("decoy_launcher") }
if #launchers == 0 then error("No decoy_launcher found", 0) end

local auto, last, status = true, -math.huge, "watching"

local function salvo(kind)
  local fired = 0
  for _, l in ipairs(launchers) do fired = fired + (l.fire(kind, CFG.salvo) or 0) end
  status = ("%s x%d at %.1f"):format(kind, fired, os.clock())
  return fired
end

-- Our own chaff echoes copy our ship's mass and size; without this they would set off the next salvo
local function looksLikeUs(t, own)
  return own ~= nil and t.mass == own.mass and t.size and own.size
    and math.abs(t.size.x - own.size.x) < 1e-6 and math.abs(t.size.y - own.size.y) < 1e-6 and math.abs(t.size.z - own.size.z) < 1e-6
end

local function friendly(t, own)
  if looksLikeUs(t, own) then return true end
  local code = t.iff and t.iff.code
  return code ~= nil and (CFG.friendCodes[code] or (own and own.iff and own.iff.code == code))
end

-- The worst threat: closest non-friendly ship that approaches fast enough
local function threat()
  local own = radar.getSelf()
  local pos = radar.getPosition()
  local svx, svy, svz = 0, 0, 0
  if own then svx, svy, svz = own.vx, own.vy, own.vz end
  local worst
  for _, t in ipairs(radar.scanSubLevels(CFG.range)) do
    if not friendly(t, own) then
      local dx, dy, dz = t.x - pos.x, t.y - pos.y, t.z - pos.z
      local d = math.max(1e-6, math.sqrt(dx * dx + dy * dy + dz * dz))
      local closing = -(dx * (t.vx - svx) + dy * (t.vy - svy) + dz * (t.vz - svz)) / d
      if closing > CFG.closing and (not worst or d < worst.d) then worst = { t = t, d = d, closing = closing } end
    end
  end
  return worst
end

local function draw(w)
  term.clear()
  term.setCursorPos(1, 1)
  print(("COUNTERMEASURES  auto %s  range %d"):format(auto and "ON" or "off", CFG.range))
  for _, l in ipairs(launchers) do
    local a = l.getAmmo()
    print(("%-18s flare %3d  chaff %3d"):format(peripheral.getName(l), a.flare, a.chaff))
  end
  if w then
    print(("THREAT %s  %.0f m  closing %.0f b/s"):format(w.t.name or (w.t.iff and w.t.iff.callsign) or w.t.id:sub(1, 8), w.d, w.closing))
  else
    print("no threat")
  end
  print(status)
  print("f flares  c chaff  a auto  q quit")
end

local function watch()
  while true do
    local w = threat()
    -- Chaff confuses radars, flares confuse seekers: release both
    if auto and w and os.clock() - last >= CFG.interval then
      salvo("chaff")
      salvo("flare")
      last = os.clock()
    end
    draw(w)
    sleep(0.25)
  end
end

local function input()
  while true do
    local _, k = os.pullEvent("key")
    if k == keys.f then salvo("flare")
    elseif k == keys.c then salvo("chaff")
    elseif k == keys.a then auto = not auto
    elseif k == keys.q then return end
  end
end

parallel.waitForAny(watch, input)
term.clear()
term.setCursorPos(1, 1)
