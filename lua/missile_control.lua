-- missile_control: fire control for createarms launchers (Create Modern Warfare) through AshCC.
--
--   missile_control                       list launchers, contacts and our missiles in flight
--   missile_control fire <target> [n]     fire n munitions (default 1) from loaded racks / VLS cells
--   missile_control cic <target> [cells]  launch through a CIC terminal (cells "1,3"; default all)
--   missile_control watch                 live view of our missiles; d = detonate all, q = quit
--   missile_control guide <id>            fire and steer: aims every missile at the radar's intercept point
--
-- <target> is a contact id from a radar (AshCC "radar" or createarms "missile_radar") or "x y z".
-- Launchers are found on any side, wired modem or link hub.

local args = { ... }

local function find(kind)
  return { peripheral.find(kind) }
end

local function launchers()
  local out = {}
  for _, p in ipairs(find("missile_rack")) do out[#out + 1] = { p = p, kind = "rack", name = peripheral.getName(p) } end
  for _, p in ipairs(find("vls")) do out[#out + 1] = { p = p, kind = "vls", name = peripheral.getName(p) } end
  return out
end

local function loaded(l)
  if l.kind == "rack" then
    local m = l.p.getMissile()
    return m ~= nil and m.firable, m and m.name
  end
  local m = l.p.getMissile()
  return l.p.isReady(), m and m.name
end

-- Contacts from every radar we can see, by id
local function contacts()
  local all = {}
  for _, r in ipairs(find("radar")) do
    for _, c in ipairs(r.scanSubLevels()) do all[c.id] = { x = c.x, y = c.y, z = c.z, name = c.name or "ship", from = "radar" } end
  end
  for _, r in ipairs(find("missile_radar")) do
    for _, c in ipairs(r.getContacts()) do
      all[c.id] = { x = c.x, y = c.y, z = c.z, name = c.type, friendly = c.friendly, from = "missile_radar" }
    end
  end
  return all
end

-- "x y z" (three args) or an id string
local function parseTarget(a, i)
  local x, y, z = tonumber(a[i]), tonumber(a[i + 1]), tonumber(a[i + 2])
  if x and y and z then return { x = x, y = y, z = z }, i + 3 end
  if a[i] then return a[i], i + 1 end
  error("give a target: contact id or x y z", 0)
end

local function ourMissiles()
  local out = {}
  for _, kind in ipairs({ "missile_rack", "vls", "cic_terminal" }) do
    for _, p in ipairs(find(kind)) do
      for _, m in ipairs(p.getMissiles()) do
        if not out[m.id] then out[m.id] = { info = m, owner = p } end
      end
    end
  end
  return out
end

local function fire(target, n)
  local fired = {}
  for _, l in ipairs(launchers()) do
    if #fired >= n then break end
    if loaded(l) then
      local id, why
      if l.kind == "rack" then id, why = l.p.fire(target) else id, why = l.p.launch(target) end
      if id then
        fired[#fired + 1] = { id = id, owner = l.p }
        print(("%s %s -> %s"):format(l.kind, l.name, id:sub(1, 8)))
      else
        print(("%s %s: %s"):format(l.kind, l.name, why))
      end
    end
  end
  if #fired == 0 then print("nothing fired (no loaded launcher)") end
  return fired
end

local function list()
  print("Launchers:")
  for _, l in ipairs(launchers()) do
    local ready, name = loaded(l)
    print(("  %-5s %-16s %s%s"):format(l.kind, l.name, name or "empty", ready and "" or " (not ready)"))
  end
  for _, c in ipairs(find("cic_terminal")) do
    print(("  cic   %-16s %d cells, queue %d"):format(peripheral.getName(c), #c.getLinkedVls(), c.getQueueSize()))
  end
  print("Contacts:")
  for id, c in pairs(contacts()) do
    print(("  %s %-14s %7.0f %5.0f %7.0f%s"):format(id:sub(1, 8), c.name, c.x, c.y, c.z, c.friendly and " (friendly)" or ""))
  end
  print("Missiles in flight:")
  for id, m in pairs(ourMissiles()) do
    print(("  %s %-12s %5.0f m/s age %4.1fs"):format(id:sub(1, 8), m.info.name, m.info.speed, m.info.age))
  end
end

-- Contact ids may be typed shortened (first characters)
local function expandId(target)
  if type(target) ~= "string" or #target >= 36 then return target end
  for id in pairs(contacts()) do
    if id:sub(1, #target) == target then return id end
  end
  return target
end

local function watch()
  while true do
    term.clear()
    term.setCursorPos(1, 1)
    print("Missiles (d = detonate all, q = quit)")
    for id, m in pairs(ourMissiles()) do
      local i = m.info
      local t = i.target and ("%.0f %.0f %.0f"):format(i.target.x, i.target.y, i.target.z) or "-"
      print(("%s %-10s %4.0fm/s %5.1fs -> %s"):format(id:sub(1, 8), i.name, i.speed, i.age, t))
    end
    local timer = os.startTimer(0.5)
    while true do
      local ev, p1 = os.pullEvent()
      if ev == "char" and p1 == "q" then return end
      if ev == "char" and p1 == "d" then
        for id, m in pairs(ourMissiles()) do m.owner.detonate(id) end
      end
      if ev == "timer" and p1 == timer then break end
    end
  end
end

-- Steer every missile we fired at the AshCC radar's lead point for the target (a better course than the
-- missile's own aim at where the target is now). Needs an AshCC radar for intercept().
local function guide(target)
  local radar = peripheral.find("radar")
  if not radar then error("guide needs an AshCC radar (for intercept)", 0) end
  local fired = fire(target, 1)
  while #fired > 0 do
    local alive = {}
    for _, f in ipairs(fired) do
      local info = f.owner.getMissileInfo(f.id)
      if info then
        alive[#alive + 1] = f
        local lead = radar.intercept(target, math.max(info.speed, 50))
        if lead then f.owner.retarget(f.id, { x = lead.x, y = lead.y, z = lead.z }) end
      else
        print(f.id:sub(1, 8) .. " gone")
      end
    end
    fired = alive
    sleep(0.25)
  end
end

local cmd = args[1]
if cmd == nil or cmd == "list" then
  list()
elseif cmd == "fire" then
  local target, i = parseTarget(args, 2)
  fire(expandId(target), tonumber(args[i]) or 1)
elseif cmd == "cic" then
  local cic = peripheral.find("cic_terminal")
  if not cic then error("no cic_terminal", 0) end
  local target, i = parseTarget(args, 2)
  local cells
  if args[i] then
    cells = {}
    for n in args[i]:gmatch("%d+") do cells[#cells + 1] = tonumber(n) end
  end
  print("queued " .. cic.launch(expandId(target), cells))
elseif cmd == "watch" then
  watch()
elseif cmd == "guide" then
  guide(expandId(args[2] or error("guide <contact id>", 0)))
else
  print("usage: missile_control [list | fire <target> [n] | cic <target> [cells] | watch | guide <id>]")
end
