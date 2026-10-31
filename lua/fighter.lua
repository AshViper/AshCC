-- Autonomous fighter AI for an AshCC aircraft.
--   fighter          fly: take off if needed, patrol around the start point, attack aircraft the radar finds
--   fighter check    the same, but measure the control surfaces again first
--   fighter info     print what was detected (parts, roles, settings) and exit
--   fighter dry      run without moving anything (bench test, e.g. on the ground)
--   fighter probe [throttle] [seconds]   flaps neutral, fixed throttle, record free flight to fighter_probe.csv
--   fighter help     list the settings (change them with: set fighter.<name> <value>)
-- On the aircraft: an AshCC radar (facing the nose), flap bearings with sails (e.g. one per wing =
-- elevons) and a thruster (creative_thruster and other setPower thrusters, or the AshCC thruster).
-- Connect them with an AshCC link hub: the hub also tells the program where each part sits.
local args = { ... }
local CMD = (args[1] or "run"):lower()

---------------------------------------------------------------- settings
local SETTINGS = {
  { "forward", "radar", "nose direction as built: radar|north|south|east|west" },
  { "vmode", "auto", "climb control: auto|lift (surfaces near CoM)|pitch (tail)" },
  { "cruise_speed", 30, "patrol speed (blocks/s)" },
  { "min_speed", 15, "slower than this: no hard turns, dive for speed" },
  { "max_speed", 80, "throttle off above this" },
  { "max_bank", 20, "bank limit (deg); raise it only if turns stay clean" },
  { "floor", "auto", "lowest altitude Y (auto: start-20 in the air, start+12 on ground)" },
  { "patrol_alt", "auto", "patrol altitude Y (auto: floor+40)" },
  { "patrol_radius", 120, "patrol circle radius around home" },
  { "fence", 1500, "fly home when further than this" },
  { "engage_range", 400, "attack targets within this range" },
  { "targets", "aircraft", "aircraft|sublevels|players|all|none" },
  { "min_target_speed", 3, "aircraft: ignore sub-levels slower than this" },
  { "target", "", "attack only names containing this" },
  { "ignore", "", "never attack names containing these (comma list)" },
  { "gun_range", 80, "firing range" },
  { "gun_speed", 0, "projectile speed for the lead (0 = aim at the target)" },
  { "fire_side", "", "redstone side wired to the guns (empty = no guns)" },
  { "fire_cone", 4, "fire when the aim error is below this (deg)" },
  { "team", "blue", "IFF team (wireless modem)" },
  { "channel", 4471, "IFF modem channel" },
  { "pitch_sign", 1, "-1 if the flaps move the wrong way for climb (pitch up)" },
  { "roll_sign", 1, "-1 if the flaps move the wrong way for roll" },
  { "roles", "", "surfaces without hub positions: name=L|R|T|N,..." },
  { "gain", 1, "overall control gain multiplier" },
  { "log", true, "write fighter_log.csv" },
}
for _, s in ipairs(SETTINGS) do
  settings.define("fighter." .. s[1], { description = s[3], default = s[2] })
end
local function opt(name) return settings.get("fighter." .. name) end
local function optNum(name, fallback)
  local v = tonumber(opt(name))
  if v == nil then return fallback end
  return v
end

-- Tuning constants (one table: Cobalt counts file-level locals against its 200 limit)
local CFG = {
  FLAP_SPEED = 360,   -- servo speed (deg/s)
  FLAP_MAX = 30,      -- largest deflection (deg)
  FLAP_REACH = 1.5,   -- the sails' centre sits this far beyond the bearing face (blocks)
  TAIL_LEVER = 0.3,   -- surfaces further than this fore/aft of the CoM steer pitch (elevator), nearer ones lift
  V_REF = 30,         -- the gains below are for this airspeed
  EFF_EXP = 1,        -- surface force ~ speed^EFF_EXP (Sable sails: linear)
  K_BANK = 1.2,       -- bank error -> roll rate (1/s)
  P_MAX = 0.3,        -- roll rate limit (rad/s)
  K_ROLL = 6,         -- deg per rad/s of roll-rate error (until measured)
  KI_ROLL = 3,        -- deg per rad*s of bank error
  I_ROLL = 8,
  -- Differential flaps tilt one wing's lift forward and the other's back (Sable sails), so rolling
  -- also yaws the nose the wrong way. Keep the differential small and back off while the nose slips.
  ROLL_MAX = 4,       -- largest differential (deg, until measured)
  SLIP_OK = 8, SLIP_SPAN = 25,     -- sideslip (deg): full authority below OK, AUTH_MIN at OK+SPAN
  AUTH_MIN = 0.35,
  SPIN_SLIP = 50,     -- sideslip (deg) that counts as a spin: controls neutral until it stops
  K_VERT = 2.5,       -- lift mode: deg per b/s of climb-rate error (until measured)
  KI_VERT = 0.8,
  I_VERT = 25,
  K_BANKFF = 4,       -- lift mode: extra deg when 1/cos(bank)-1 = 1 (until measured)
  K_THETA = 0.05,     -- pitch mode: rad of pitch per b/s of climb-rate error
  KI_THETA = 0.02,
  K_PITCH = 2.0,      -- pitch mode: pitch error -> pitch rate (1/s)
  Q_MAX = 0.6,
  K_Q = 20,           -- pitch mode: deg per rad/s of pitch-rate error (until measured)
  KI_Q = 0.5,         -- pitch mode: trim follows the rate error (1/s)
  K_SPD = 0.06,       -- throttle per b/s of speed error
  KI_SPD = 0.02,
  K_HDG = 0.7,        -- heading error -> turn rate (1/s)
  K_ALT = 0.2,        -- altitude error -> climb rate (1/s)
  VY_MAX = 12,
  GAMMA_MAX = 0.5,    -- steepest commanded climb/dive (rad)
  LOOKAHEAD = 3,      -- floor prediction (s)
  -- surface check: open-loop doublets, then gains for these closed-loop speeds (1/s)
  CHECK_ROLL = 8, CHECK_VERT = 6, CHECK_HALF = 0.6, CHECK_SKIP = 0.15,
  W_VERT = 1.5, W_Q = 3,
  ID_AMP = 5, ID_UNIT = 0.25, ID_FREE = 1.5,   -- lateral identification: 3-2-1-1 steps, then hands off
  -- LQR: the size of each error that costs as much as LQR_U degrees of differential
  Q_P = 0.4, Q_R = 0.1, Q_BETA = 3, Q_BANK = 12, Q_INT = 8,   -- rad/s, rad/s, deg, deg, deg*s
  LQR_U = 3,
  LAT_MAX = 8,        -- largest differential the lateral controller may use (deg)
  BANK_RATE = 8,      -- commanded bank changes at most this fast (deg/s)
  -- tactics
  SCAN_EVERY = 1.0,
  LOST_AFTER = 3,
  TRAIL = 0.6,        -- follow the target at this fraction of gun range
  BREAK_RANGE = 14,   -- closer than this (plus sizes) and closing: break away
  THREAT_CONE = 25,   -- a contact is a threat when its velocity points at us within this (deg)
  EVADE_MIN = 3,
  LOG_MAX = 400000,
}

for k, v in pairs(_G.FIGHTER_CFG or {}) do CFG[k] = v end   -- test hook

---------------------------------------------------------------- math
local abs, sqrt, sin, cos, floor = math.abs, math.sqrt, math.sin, math.cos, math.floor
local atan2 = math.atan2 or math.atan
local RAD, DEG = math.pi / 180, 180 / math.pi
local function clamp(x, lo, hi) if x < lo then return lo elseif x > hi then return hi end return x end
local function wrap(a) return (a + math.pi) % (2 * math.pi) - math.pi end
local function asin(x) return math.asin(clamp(x, -1, 1)) end
local function v3(x, y, z) return { x = x, y = y, z = z } end
local function vt(t) return v3(t.x or 0, t.y or 0, t.z or 0) end
local function add(a, b) return v3(a.x + b.x, a.y + b.y, a.z + b.z) end
local function sub(a, b) return v3(a.x - b.x, a.y - b.y, a.z - b.z) end
local function mul(a, s) return v3(a.x * s, a.y * s, a.z * s) end
local function dot(a, b) return a.x * b.x + a.y * b.y + a.z * b.z end
local function cross(a, b) return v3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x) end
local function len(a) return sqrt(dot(a, a)) end
local function norm(a)
  local l = len(a)
  if l < 1e-9 then return v3(0, 0, 0) end
  return mul(a, 1 / l)
end
local function angleBetween(a, b)
  local la, lb = len(a), len(b)
  if la < 1e-9 or lb < 1e-9 then return math.pi end
  return math.acos(clamp(dot(a, b) / (la * lb), -1, 1))
end
-- Quaternions {w,x,y,z}; qrot turns a vector by q
local function qconj(q) return { w = q.w, x = -q.x, y = -q.y, z = -q.z } end
local function qmul(a, b)
  return { w = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
           x = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
           y = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
           z = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w }
end
local function qrot(q, v)
  local tx, ty, tz = 2 * (q.y * v.z - q.z * v.y), 2 * (q.z * v.x - q.x * v.z), 2 * (q.x * v.y - q.y * v.x)
  return v3(v.x + q.w * tx + q.y * tz - q.z * ty,
            v.y + q.w * ty + q.z * tx - q.x * tz,
            v.z + q.w * tz + q.x * ty - q.y * tx)
end
-- Linear fit slope of {t, v} samples
local function slope(s)
  local n = #s
  if n < 2 then return nil end
  local mt, mv = 0, 0
  for _, p in ipairs(s) do mt, mv = mt + p[1], mv + p[2] end
  mt, mv = mt / n, mv / n
  local num, den = 0, 0
  for _, p in ipairs(s) do num, den = num + (p[1] - mt) * (p[2] - mv), den + (p[1] - mt) ^ 2 end
  if den < 1e-9 then return nil end
  return num / den
end

local DIRS = {
  north = v3(0, 0, -1), south = v3(0, 0, 1), east = v3(1, 0, 0),
  west = v3(-1, 0, 0), up = v3(0, 1, 0), down = v3(0, -1, 0),
}
-- Nearest of the six axis directions: vector, name and how close (cos)
local function snap(v)
  local best, bestName, bestDot = DIRS.up, "up", -2
  local l = len(v)
  if l < 1e-9 then return best, bestName, 0 end
  for _, name in ipairs({ "north", "south", "east", "west", "up", "down" }) do
    local c = dot(v, DIRS[name]) / l
    if c > bestDot then best, bestName, bestDot = DIRS[name], name, c end
  end
  return best, bestName, bestDot
end

---------------------------------------------------------------- state
local G = { surf = {}, thr = {}, vscale = 1 }      -- devices and geometry
local E = {}                                       -- estimated flight state
local C = { iroll = 0, ivert = 0, ith = 0, ithr = 0.5, gr = 1, gv = 1, eint = 0, uhist = {} }   -- controller memory
local L = {}                                       -- gains measured by the check (saved)
local S = { mode = "INIT", cmd = {}, pending = {}, notes = {}, t0 = os.clock() }
local T = { list = {}, friends = {}, scanT = -1e9 }  -- targets
local STATE_FILE, LOG_FILE, EVENT_FILE = "fighter_state.txt", "fighter_log.csv", "fighter_events.log"

local function note(fmt, ...)
  local line = string.format("%5.1f " .. fmt, os.clock() - S.t0, ...)
  table.insert(S.notes, 1, line)
  S.notes[5] = nil
  if opt("log") and not S.dry then
    pcall(function()
      if fs.exists(EVENT_FILE) and fs.getSize(EVENT_FILE) > CFG.LOG_MAX / 4 then fs.delete(EVENT_FILE) end
      local f = fs.open(EVENT_FILE, "a")
      f.writeLine(line)
      f.close()
    end)
  end
end

local function setMode(m)
  if S.mode ~= m then
    S.mode, S.modeT = m, os.clock()
    note("mode %s", m)
  end
end

local function queue(f) S.pending[#S.pending + 1] = f end

---------------------------------------------------------------- devices
local function hasType(name, t)
  if peripheral.hasType then return peripheral.hasType(name, t) end
  return peripheral.getType(name) == t
end

local function discover()
  G.radar = peripheral.find("radar")
  if not G.radar then error("No AshCC radar found (attach it or link it with a link hub)", 0) end
  G.radarName = peripheral.getName(G.radar)
  G.flaps = {}
  for _, name in ipairs(peripheral.getNames()) do
    local p = peripheral.wrap(name)
    if hasType(name, "flap_bearing") then
      G.flaps[#G.flaps + 1] = { name = name, p = p }
    elseif hasType(name, "quad_flap_bearing") then
      G.flaps[#G.flaps + 1] = { name = name, p = p, quad = true }
    else
      local ty = peripheral.getType(name)
      if type(ty) == "string" and ty:find("thruster") then
        if p.setPowerNormalized and p.setPower then
          G.thr[#G.thr + 1] = { name = name, p = p, steps = 15 }
        elseif p.setThrust and p.getMaxThrust then
          G.thr[#G.thr + 1] = { name = name, p = p, steps = 100, ashcc = true }
        end
      end
    end
  end
  table.sort(G.flaps, function(a, b) return a.name < b.name end)
  table.sort(G.thr, function(a, b) return a.name < b.name end)
  if #G.flaps == 0 then error("No flap bearings found", 0) end
  if #G.thr == 0 then error("No thruster found", 0) end
  G.modem = peripheral.find("modem", function(_, m) return m.isWireless and m.isWireless() end)
  G.monitor = peripheral.find("monitor")
end

local computeMixing, flipNose

local function parseRoles(s)
  local out = {}
  for item in tostring(s or ""):gmatch("[^,]+") do
    local k, v = item:match("^%s*([^=%s]+)%s*=%s*(%a)%s*$")
    if k then out[k] = v:upper() end
  end
  return out
end

-- Works out the aircraft axes and what each control surface does.
-- Sable's orientation turns as-built (plot) vectors into world vectors; a flap turns about the
-- positive axis of its bearing, so its sails push towards axis x flightDirection (more for more speed).
local function setupGeometry()
  local radar = G.radar
  -- One batch = one tick, so the three readings belong together even while flying
  local me, ori, rpos
  parallel.waitForAll(function() me = radar.getSelf() end, function() ori = radar.getOrientation() end,
    function() rpos = radar.getPosition() end)
  if not me then error("The radar is not on a Sable aircraft (radar.getSelf() is nil)", 0) end
  E.selfId = me.id
  local q = { w = me.orientation.w, x = me.orientation.x, y = me.orientation.y, z = me.orientation.z }
  local function quality(qq)
    local _, _, c1 = snap(qrot(qconj(qq), vt(ori.forward)))
    local _, _, c2 = snap(qrot(qconj(qq), vt(ori.up)))
    return math.min(c1, c2)
  end
  G.qconj = quality(q) < 0.95 and quality(qconj(q)) > quality(q)
  if G.qconj then q = qconj(q) end
  local qi = qconj(q)
  local radarF, radarFName = snap(qrot(qi, vt(ori.forward)))
  G.radarFwd = radarFName

  local fwd = tostring(opt("forward") or "radar"):lower()
  local F = fwd == "radar" and radarF or DIRS[fwd]
  if not F or abs(F.y) > 0.5 then
    error("Cannot use '" .. fwd .. "' (radar faces " .. radarFName .. ") as the nose. Set: set fighter.forward north|south|east|west", 0)
  end
  G.F, G.U = F, DIRS.up
  G.R = cross(G.F, G.U)
  G.fwdName = select(2, snap(G.F))

  -- Positions (plot coordinates) from link hubs
  local pos = {}
  for _, hub in ipairs({ peripheral.find("link_hub") }) do
    for _, l in ipairs(hub.getLinks()) do pos[l.name] = v3(l.x + 0.5, l.y + 0.5, l.z + 0.5) end
  end
  local com
  if pos[G.radarName] then
    com = add(pos[G.radarName], qrot(qi, sub(v3(me.x, me.y, me.z), vt(rpos))))
  else
    local n, sum = 0, v3(0, 0, 0)
    for _, f in ipairs(G.flaps) do
      if pos[f.name] then n, sum = n + 1, add(sum, pos[f.name]) end
    end
    if n > 0 then com = mul(sum, 1 / n); G.comGuess = true end
  end
  G.com = com

  local roles = parseRoles(opt("roles"))
  G.surf = {}
  for _, f in ipairs(G.flaps) do
    if f.quad then
      for _, side in ipairs(f.p.getSides()) do
        local face = DIRS[side]
        if face then
          G.surf[#G.surf + 1] = { dev = f, label = f.name .. ":" .. side, face = face,
            set = function(a) f.p.setAngle(side, a) end }
        end
      end
    else
      G.surf[#G.surf + 1] = { dev = f, label = f.name, face = (snap(qrot(qi, vt(f.p.getAxis())))),
        set = function(a) f.p.setAngle(a) end }
    end
  end
  for _, s in ipairs(G.surf) do
    s.axis = v3(abs(s.face.x), abs(s.face.y), abs(s.face.z))
    s.faceName = select(2, snap(s.face))
    local role = roles[s.label]
    if role == "L" then s.r = mul(G.R, -2)
    elseif role == "R" then s.r = mul(G.R, 2)
    elseif role == "T" then s.r = mul(G.F, -3)
    elseif role == "N" then s.r = nil
    elseif pos[s.dev.name] and com then
      s.r = sub(add(pos[s.dev.name], mul(s.face, CFG.FLAP_REACH)), com)
    end
  end

  computeMixing()
  return me
end

-- What each surface does for the current nose direction G.F, and the (roll, vertical) mixing
computeMixing = function()
  G.R = cross(G.F, G.U)
  G.fwdName = select(2, snap(G.F))
  for _, s in ipairs(G.surf) do
    s.eff = nil
    if s.r then
      local dF = cross(s.axis, G.F)
      local dM = cross(s.r, dF)
      s.eff = { roll = dot(dM, G.F), pitch = dot(dM, G.R), yaw = dot(dM, G.U), lift = dot(dF, G.U) }
    end
  end
  -- Lift or pitch: how far fore/aft the surfaces sit from the centre of mass
  local sumLift, sumPitch = 0, 0
  for _, s in ipairs(G.surf) do
    if s.eff then sumLift, sumPitch = sumLift + abs(s.eff.lift), sumPitch + abs(s.eff.pitch) end
  end
  G.lever = sumLift > 0.1 and sumPitch / sumLift or 0
  local vm = tostring(opt("vmode") or "auto"):lower()
  if vm == "lift" or vm == "pitch" then G.vmode = vm
  else G.vmode = G.lever > CFG.TAIL_LEVER and "pitch" or "lift" end

  -- Mixing: least-squares split of (roll, vertical) demands over the surfaces
  local m11, m12, m22 = 1e-9, 0, 1e-9
  for _, s in ipairs(G.surf) do
    if s.eff then
      local a, b = s.eff.roll, G.vmode == "lift" and s.eff.lift or s.eff.pitch
      s.rowR, s.rowV = a, b
      m11, m12, m22 = m11 + a * a, m12 + a * b, m22 + b * b
    end
  end
  local det = m11 * m22 - m12 * m12
  local independent = det > 1e-3 * m11 * m22
  local maxR, maxV = 0, 0
  for _, s in ipairs(G.surf) do
    s.mr, s.mv = 0, 0
    if s.eff then
      if independent then
        s.mr = (s.rowR * m22 - s.rowV * m12) / det
        s.mv = (s.rowV * m11 - s.rowR * m12) / det
      else
        s.mr, s.mv = s.rowR, s.rowV
      end
      maxR, maxV = math.max(maxR, abs(s.mr)), math.max(maxV, abs(s.mv))
    end
  end
  for _, s in ipairs(G.surf) do
    s.mr = maxR > 1e-9 and s.mr / maxR or 0
    s.mv = maxV > 1e-9 and s.mv / maxV or 0
  end
  G.canRoll, G.canVert = maxR > 1e-6 and m11 > 0.05, maxV > 1e-6 and m22 > 0.05

  local key = { G.fwdName, G.vmode, "r" .. tostring(opt("roll_sign")), "v" .. tostring(opt("pitch_sign")) }
  for _, s in ipairs(G.surf) do key[#key + 1] = s.label .. "/" .. s.faceName .. string.format("/%.1f/%.1f", s.mr, s.mv) end
  G.key = table.concat(key, " ")
end

local function loadState()
  L = { kroll = CFG.K_ROLL, kvert = CFG.K_VERT, kivert = CFG.KI_VERT, kbank = CFG.K_BANKFF, kq = CFG.K_Q,
        rollMax = CFG.ROLL_MAX, pmax = CFG.P_MAX, rollSign = 1, vertSign = 1, checked = false }
  if fs.exists(STATE_FILE) then
    local f = fs.open(STATE_FILE, "r")
    local t = textutils.unserialize(f.readAll())
    f.close()
    if type(t) == "table" and t.key == G.key then
      for k, v in pairs(t) do L[k] = v end
    end
  end
  L.key = G.key
  local rs = optNum("roll_sign", 1) < 0 and -1 or 1
  local vs = optNum("pitch_sign", 1) < 0 and -1 or 1
  for _, s in ipairs(G.surf) do s.mr, s.mv = s.mr * L.rollSign * rs, s.mv * L.vertSign * vs end
end

local function saveState()
  local ok = pcall(function()
    local f = fs.open(STATE_FILE, "w")
    f.write(textutils.serialize(L))
    f.close()
  end)
  if not ok then note("state not saved") end
end

local function geometryReport(out)
  out(string.format("nose %s (radar faces %s)%s", G.fwdName, G.radarFwd, G.qconj and ", quaternion conjugated" or ""))
  out(string.format("climb control: %s (surface lever %.1f blocks)%s", G.vmode, G.lever,
    G.comGuess and ", CoM guessed (radar not in hub)" or ""))
  for _, s in ipairs(G.surf) do
    if s.eff then
      out(string.format(" %-18s axis %-5s at R%+.1f F%+.1f  roll %+.2f vert %+.2f",
        s.label, s.faceName, dot(s.r, G.R), dot(s.r, G.F), s.mr, s.mv))
    else
      out(string.format(" %-18s axis %-5s  unused (no position: link hub or fighter.roles)", s.label, s.faceName))
    end
  end
  for _, t in ipairs(G.thr) do out(" thruster " .. t.name) end
  if not G.canRoll then out("WARNING: no surface can roll the aircraft") end
  if not G.canVert then out("WARNING: no surface for climb control") end
  out(string.format("climb gains: vert %.2f bankFF %.1f q %.1f  %s", L.kvert, L.kbank, L.kq,
    L.checked and "(measured)" or "(defaults, measured in flight)"))
  if L.lat then
    out(string.format("roll: measured model (delay %d, fit %.2f/%.2f at %.0f b/s)%s", L.lat.d, L.lat.r2p, L.lat.r2r,
      L.lat.V0, G.latK and "" or " - LQR FAILED"))
  else
    out("roll: gentle bank hold until the in-flight check measures the aircraft")
  end
end

---------------------------------------------------------------- estimation
local function estimate(dt)
  local me = S.me
  local q = { w = me.orientation.w, x = me.orientation.x, y = me.orientation.y, z = me.orientation.z }
  if G.qconj then q = qconj(q) end
  if E.q and q.w * E.q.w + q.x * E.q.x + q.y * E.q.y + q.z * E.q.z < 0 then
    q = { w = -q.w, x = -q.x, y = -q.y, z = -q.z }
  end
  local pos = v3(me.x, me.y, me.z)
  local raw = v3(me.vx or 0, me.vy or 0, me.vz or 0)
  -- The velocity should be in blocks/s; compare with the movement once and fix it if not
  if not G.vchecked and len(raw) > 0.5 and E.pos then
    local vc = G.vc or { t = 0, d = v3(0, 0, 0), p0 = E.pos }
    G.vc = vc
    vc.t, vc.d = vc.t + dt, add(vc.d, mul(raw, dt))
    if vc.t > 1.5 and len(vc.d) > 1 then
      local r = len(sub(pos, vc.p0)) / len(vc.d)
      if r > 10 then G.vscale = 20
      elseif r < 0.5 or r > 2 then G.vdiff = true end
      G.vchecked = true
      if G.vscale ~= 1 or G.vdiff then note("velocity scale %.2f, using %s", r, G.vdiff and "position steps" or "x20") end
    end
  end
  local vel = mul(raw, G.vscale)
  if G.vdiff and E.pos then
    vel = add(mul(E.vel, 0.6), mul(sub(pos, E.pos), 0.4 / dt))
  end
  if E.q then
    local dq = qmul(qconj(E.q), q)
    if dq.w < 0 then dq = { w = -dq.w, x = -dq.x, y = -dq.y, z = -dq.z } end
    local w = mul(v3(dq.x, dq.y, dq.z), 2 / dt)
    E.p = 0.5 * (E.p or 0) + 0.5 * dot(w, G.F)
    E.qr = 0.5 * (E.qr or 0) + 0.5 * dot(w, G.R)
    E.yr = 0.5 * (E.yr or 0) + 0.5 * dot(w, G.U)
  end
  if E.vel then
    E.ay = 0.6 * (E.ay or 0) + 0.4 * (vel.y - E.vel.y) / dt
  end
  E.q, E.pos, E.vel = q, pos, vel
  E.Fw, E.Uw, E.Rw = qrot(q, G.F), qrot(q, G.U), qrot(q, G.R)
  E.speed = len(vel)
  E.dir = E.speed > 2 and mul(vel, 1 / E.speed) or E.Fw
  E.bank = atan2(-E.Rw.y, E.Uw.y)
  E.pitch = asin(E.Fw.y)
  -- sideslip: + = flying towards the right of the nose
  E.beta = E.speed > 3 and atan2(dot(vel, E.Rw), dot(vel, E.Fw)) or 0
  E.p, E.qr, E.yr, E.ay = E.p or 0, E.qr or 0, E.yr or 0, E.ay or 0
end

---------------------------------------------------------------- targets
local function contactSpeed(c) return sqrt((c.vx or 0) ^ 2 + (c.vy or 0) ^ 2 + (c.vz or 0) ^ 2) end

local function nameMatches(c)
  local name = tostring(c.name or ""):lower()
  for ig in tostring(opt("ignore") or ""):lower():gmatch("[^,]+") do
    ig = ig:match("^%s*(.-)%s*$")
    if ig ~= "" and name:find(ig, 1, true) then return false end
  end
  return true
end

local function isFriend(c)
  local seen = T.friends[c.id]
  return seen ~= nil and os.clock() - seen < 10
end

local function canAttack(c)
  if c.id == E.selfId or isFriend(c) or not nameMatches(c) then return false end
  local only = tostring(opt("target") or ""):lower()
  if only ~= "" and not tostring(c.name or ""):lower():find(only, 1, true) then return false end
  local mode = tostring(opt("targets") or "aircraft"):lower()
  if mode == "none" then return false end
  if c.kind == "entity" then return mode == "players" or mode == "all" end
  if mode == "players" then return false end
  if mode == "aircraft" and contactSpeed(c) < optNum("min_target_speed", 3) then return false end
  return true
end

-- Contact positions move on between scans
local function contactNow(c)
  local age = os.clock() - (c.seen or os.clock())
  return v3(c.x + (c.vx or 0) * age, c.y + (c.vy or 0) * age, c.z + (c.vz or 0) * age), v3(c.vx or 0, c.vy or 0, c.vz or 0)
end

local function pickTarget()
  local best, bestScore, curValid
  local now = os.clock()
  for _, c in ipairs(T.list) do
    if canAttack(c) then
      local p = contactNow(c)
      local r = sub(p, E.pos)
      local d = len(r)
      local isCur = T.cur and c.id == T.cur.id
      if d <= optNum("engage_range", 400) * (isCur and 1.5 or 1) then
        local score = d * (1.5 - 0.5 * cos(angleBetween(r, E.dir)))
        if isCur then score, curValid = score * 0.6, true end
        if not bestScore or score < bestScore then best, bestScore = c, score end
      end
    end
  end
  if curValid then T.validT = now end
  if best and (not T.cur or T.cur.id ~= best.id) then
    T.cur = { id = best.id, name = best.name or best.type or "?", kind = best.kind }
    T.c, T.cT, T.validT = best, now, now
    note("target %s", T.cur.name)
  elseif T.cur and not curValid and now - (T.validT or 0) > CFG.LOST_AFTER then
    note("target dropped")
    T.cur, T.c = nil, nil
  end
end

-- The most dangerous contact behind us that points at us (nil if none)
local function findThreat()
  local worst, worstD
  local range = 2 * optNum("gun_range", 80) + 40
  for _, c in ipairs(T.list) do
    if c.id ~= E.selfId and not isFriend(c) and nameMatches(c) and (c.kind ~= "entity") then
      local p, v = contactNow(c)
      if T.c and c.id == T.c.id then p, v = vt(T.c), v3(T.c.vx or 0, T.c.vy or 0, T.c.vz or 0) end
      local r = sub(p, E.pos)
      local d = len(r)
      if d < range and len(v) > 5 and dot(norm(r), E.dir) < -0.3
        and angleBetween(v, mul(r, -1)) < CFG.THREAT_CONE * RAD then
        if not worstD or d < worstD then worst, worstD = c, d end
      end
    end
  end
  return worst, worstD
end

---------------------------------------------------------------- guidance
-- Bank angle that turns the flight path towards horizontal direction d
local function bankTowards(d, maxBank)
  local vh = norm(v3(E.dir.x, 0, E.dir.z))
  if len(vh) < 0.5 then vh = norm(v3(E.Fw.x, 0, E.Fw.z)) end
  local right = v3(-vh.z, 0, vh.x)
  local dh = norm(v3(d.x, 0, d.z))
  if len(dh) < 0.5 then return 0, 0 end
  local err = atan2(dot(dh, right), dot(dh, vh))
  local omega = CFG.K_HDG * err
  local bank = math.atan(math.max(E.speed, 5) * omega / 9.8)
  return clamp(bank, -maxBank, maxBank), err
end

local function climbTowards(d)
  local gamma = clamp(asin(d.y / math.max(len(d), 1e-6)), -CFG.GAMMA_MAX, CFG.GAMMA_MAX)
  return math.max(E.speed, 10) * sin(gamma)
end

local function holdAltitude(alt)
  return clamp(CFG.K_ALT * (alt - E.pos.y), -CFG.VY_MAX, CFG.VY_MAX)
end

-- Time t at which a projectile at speed u from here meets a target at p moving with v
local function leadTime(r, v, u)
  local a = dot(v, v) - u * u
  local b = 2 * dot(r, v)
  local c = dot(r, r)
  local t
  if abs(a) < 1e-6 then
    if b < 0 then t = -c / b end
  else
    local disc = b * b - 4 * a * c
    if disc >= 0 then
      local sq = sqrt(disc)
      local t1, t2 = (-b - sq) / (2 * a), (-b + sq) / (2 * a)
      if t1 > 0 and (t2 <= 0 or t1 < t2) then t = t1 elseif t2 > 0 then t = t2 end
    end
  end
  return t
end

local function patrolDirection()
  local rel = sub(E.pos, S.home)
  rel.y = 0
  local d = len(rel)
  local radial = d > 1 and mul(rel, 1 / d) or v3(1, 0, 0)
  local tangent = v3(-radial.z, 0, radial.x)        -- clockwise seen from above
  local R = S.patrolR
  local chi = math.atan(1.5 * (d - R) / R)          -- outside: turn in, inside: turn out
  return add(mul(tangent, cos(chi)), mul(radial, -sin(chi)))
end

---------------------------------------------------------------- tactics
local function inCheck() return S.mode == "CHECK" end

local function wantCheck()
  if S.dry or not G.canRoll then return false end
  return S.forceCheck or not L.checked
end

local function decide(dt)
  local cmd = S.cmd
  local y, spd = E.pos.y, E.speed
  local now = os.clock()
  local gunRange = optNum("gun_range", 80)
  local cruise = optNum("cruise_speed", 30)
  local minSpd, maxSpd = optNum("min_speed", 15), optNum("max_speed", 80)
  local maxBank = clamp(optNum("max_bank", 60), 10, 85) * RAD
  -- Lift mode: no steeper than the collective can still hold height in (1/cos(bank)-1 extra lift)
  if G.vmode == "lift" and L.kbank > 0.5 then
    maxBank = math.min(maxBank, math.acos(1 / (1 + 0.6 * CFG.FLAP_MAX / L.kbank)))
  end
  -- If climb control saturates in turns, bank less for a while
  if C.vsat and cmd.vy and cmd.vy - E.vel.y > 3 then S.bankRelief = math.min((S.bankRelief or 0) + 12 * RAD * dt, maxBank - 20 * RAD)
  else S.bankRelief = math.max((S.bankRelief or 0) - 4 * RAD * dt, 0) end
  maxBank = maxBank - S.bankRelief
  cmd.thr, cmd.speed, cmd.check, cmd.fire = nil, cruise, nil, false

  local threat = nil
  if not inCheck() then threat = findThreat() end
  local airborne = not S.ground

  -- Upset: rolled past 100 deg, diving steeply, or the nose swinging round (spin: controls neutral)
  -- Thrust carries us tail first: the nose is the other way round
  if spd > 6 and (S.thr or 0) > 0.3 and abs(E.beta) > 135 * RAD then
    S.revT = (S.revT or 0) + dt
    if S.revT > 1 then S.revT = 0; flipNose() end
    if S.ground then cmd.bank, cmd.vy, cmd.thr = 0, 0, 1; return end
  else
    S.revT = 0
  end
  local spinning = airborne and spd > 5 and abs(E.beta) > CFG.SPIN_SLIP * RAD and abs(E.beta) < 135 * RAD
  if airborne and (spinning or abs(E.bank) > 100 * RAD or E.pitch < -55 * RAD or (S.mode == "RECOVER"
      and (abs(E.bank) > 30 * RAD or E.pitch < -20 * RAD or (S.spin and abs(E.beta) > 15 * RAD)))) then
    if spinning and not S.spin then note("spin") end
    S.spin = S.spin or spinning
    setMode("RECOVER")
    cmd.bank, cmd.vy = 0, 0
    cmd.thr = spd > maxSpd and 0 or 1
    return
  end
  S.spin = false
  -- Ground ahead: pull up
  local predict = y + E.vel.y * CFG.LOOKAHEAD + 0.5 * math.min(E.ay, 0) * CFG.LOOKAHEAD ^ 2
  if airborne and (predict < S.floor or y < S.floor or (S.mode == "PULLUP" and (y < S.floor + 10 or E.vel.y < 0))) then
    setMode("PULLUP")
    cmd.bank, cmd.vy, cmd.thr = 0, CFG.VY_MAX, 1
    return
  end
  -- Ground start: full power, wings level, climb once fast enough
  if S.ground then
    setMode("TAKEOFF")
    cmd.bank, cmd.vy, cmd.thr = 0, 3, 1
    if y > S.startY + 6 then S.ground = false; note("airborne") end
    return
  end
  -- Measure the surfaces once (straight and level, no threats around)
  if wantCheck() and S.mode ~= "CHECK" and not threat and spd >= math.max(minSpd + 3, 0.7 * cruise)
    and y > S.floor + 15 and abs(E.bank) < 15 * RAD and abs(E.beta) < 10 * RAD then
    S.chk = { step = 1, t = now, samples = {}, res = {}, lat = {} }
    setMode("CHECK")
  end
  if S.mode == "CHECK" then
    if y < S.floor + 12 or spd < minSpd then
      note("check aborted")
      S.chk = nil
      setMode("PATROL")
    else
      S.checkHeading = S.checkHeading or v3(E.dir.x, 0, E.dir.z)
      cmd.bank = bankTowards(S.checkHeading, 15 * RAD)
      cmd.vy = holdAltitude(S.chk.alt or y)
      S.chk.alt = S.chk.alt or y
      cmd.check = true
      return
    end
  end
  S.checkHeading = nil

  -- Being chased: break turn into the threat, full power, change height, jink
  if threat or (S.mode == "EVADE" and now - S.modeT < CFG.EVADE_MIN) then
    if threat then S.threat = threat end
    setMode("EVADE")
    local tp = contactNow(S.threat)
    local r = sub(tp, E.pos)
    local right = v3(-E.dir.z, 0, E.dir.x)
    local side = dot(r, right) >= 0 and 1 or -1
    if floor((now - S.modeT) / 3) % 2 == 1 then side = -side end
    cmd.bank = side * maxBank
    cmd.vy = (y > S.floor + 50) and -6 or 6
    if floor((now - S.modeT) / 4) % 2 == 1 then cmd.vy = -cmd.vy * 0.5 end
    cmd.thr = 1
    return
  end
  S.threat = nil

  -- Too far from home
  local homeD = len(v3(E.pos.x - S.home.x, 0, E.pos.z - S.home.z))
  local fence = optNum("fence", 1500)
  if homeD > fence or (S.mode == "RTB" and homeD > fence * 0.6) then
    setMode("RTB")
    cmd.bank = bankTowards(sub(S.home, E.pos), maxBank)
    cmd.vy = holdAltitude(S.patrolAlt)
    return
  end

  local tc = T.cur and T.c
  if tc and now - (S.disengage or 0) > 0 then
    local tp, tv = vt(tc), v3(tc.vx or 0, tc.vy or 0, tc.vz or 0)
    local r = sub(tp, E.pos)
    local range = len(r)
    local closure = -dot(sub(tv, E.vel), norm(r))
    local size = 0
    if tc.size then size = math.max(tc.size.x or 0, tc.size.y or 0, tc.size.z or 0) end
    S.tgtRange, S.closure = range, closure
    -- Head-on or about to collide: break away for a moment
    if (range < CFG.BREAK_RANGE + size and closure > 0) or (S.mode == "BREAK" and now - S.modeT < 2.5) then
      setMode("BREAK")
      local right = v3(-E.dir.z, 0, E.dir.x)
      local side = dot(r, right) >= 0 and -1 or 1
      cmd.bank = side * maxBank
      cmd.vy = (tp.y < y) and 6 or -4
      if y < S.floor + 30 then cmd.vy = 6 end
      cmd.thr = 1
      return
    end
    local gunSpeed = optNum("gun_speed", 0)
    local aim
    if range < 1.5 * gunRange then
      setMode("ATTACK")
      if gunSpeed > 0 then
        local t = leadTime(r, sub(tv, E.vel), gunSpeed) or range / gunSpeed
        aim = add(tp, mul(sub(tv, E.vel), t))
      else
        aim = tp
      end
      -- Follow at trail distance, matching the target's speed
      local along = dot(tv, norm(r))
      cmd.speed = clamp(along + 0.4 * (range - CFG.TRAIL * gunRange), minSpd + 3, maxSpd)
    else
      setMode("ENGAGE")
      local t = leadTime(r, tv, math.max(spd, cruise)) or range / math.max(spd, 1)
      aim = add(tp, mul(tv, math.min(t, 20)))
      -- Aim behind it so we arrive on its tail rather than head-on
      if len(tv) > 2 then aim = sub(aim, mul(norm(tv), CFG.TRAIL * gunRange)) end
      cmd.speed = maxSpd
    end
    if aim.y < S.floor + 10 then aim.y = S.floor + 10 end
    local d = sub(aim, E.pos)
    cmd.bank = bankTowards(d, maxBank)
    cmd.vy = climbTowards(d)
    S.aimErr = angleBetween(E.Fw, d)
    cmd.fire = S.mode == "ATTACK" and range <= gunRange and S.aimErr < optNum("fire_cone", 4) * RAD
    return
  end

  setMode("PATROL")
  S.tgtRange, S.aimErr = nil, nil
  cmd.bank = bankTowards(patrolDirection(), math.min(maxBank, 35 * RAD))
  cmd.vy = holdAltitude(S.patrolAlt)
end

-- Limits that apply to every mode
local function limits()
  local cmd = S.cmd
  local minSpd = optNum("min_speed", 15)
  if not S.ground and E.speed < minSpd and S.mode ~= "PULLUP" and S.mode ~= "RECOVER" then
    cmd.bank = clamp(cmd.bank, -25 * RAD, 25 * RAD)
    if E.pos.y > S.floor + 15 then cmd.vy = math.min(cmd.vy, -2) end
    cmd.thr = 1
  end
  if E.pos.y < S.floor + 15 then cmd.vy = math.max(cmd.vy, 0) end
  cmd.vy = clamp(cmd.vy, -CFG.VY_MAX, CFG.VY_MAX)
end

---------------------------------------------------------------- lateral model
-- Roll and yaw are strongly coupled on these aircraft (differential flaps yaw the nose, a yawing wing
-- rolls because the outer sails go faster), so a fixed roll gain can even act backwards. Instead the
-- check measures a linear model per control step (u = differential in deg, acting d steps later):
--   dp = a1 p + a2 r + a3 beta + a4 u + a5,  dr = (same terms),  dbeta = ... + phi term
-- and an LQR on (p, r, beta, bank error, its integral, past inputs) gives the feedback gains.

local function solveLin(A, b, n)
  for c = 1, n do
    local piv, best = c, abs(A[c][c])
    for r = c + 1, n do if abs(A[r][c]) > best then piv, best = r, abs(A[r][c]) end end
    if best < 1e-12 then return nil end
    A[c], A[piv], b[c], b[piv] = A[piv], A[c], b[piv], b[c]
    for r = c + 1, n do
      local f = A[r][c] / A[c][c]
      if f ~= 0 then
        for k = c, n do A[r][k] = A[r][k] - f * A[c][k] end
        b[r] = b[r] - f * b[c]
      end
    end
  end
  local x = {}
  for r = n, 1, -1 do
    local s = b[r]
    for k = r + 1, n do s = s - A[r][k] * x[k] end
    x[r] = s / A[r][r]
  end
  return x
end

-- Least squares y ~ X theta (columns scaled first). Returns theta, R^2, residual sum of squares.
local function fit(X, y)
  local n, m = #X[1], #X
  local sc = {}
  for j = 1, n do
    local s = 0
    for i = 1, m do s = s + X[i][j] ^ 2 end
    sc[j] = sqrt(s / m)
    if sc[j] < 1e-9 then sc[j] = 1 end
  end
  local A, b = {}, {}
  for i = 1, n do
    A[i], b[i] = {}, 0
    for j = 1, n do A[i][j] = i == j and 1e-6 * m or 0 end
  end
  for k = 1, m do
    local x = X[k]
    for i = 1, n do
      local xi = x[i] / sc[i]
      b[i] = b[i] + xi * y[k]
      for j = 1, n do A[i][j] = A[i][j] + xi * x[j] / sc[j] end
    end
  end
  local th = solveLin(A, b, n)
  if not th then return nil end
  for j = 1, n do th[j] = th[j] / sc[j] end
  local mean, sst, sse = 0, 0, 0
  for k = 1, m do mean = mean + y[k] end
  mean = mean / m
  for k = 1, m do
    local e = y[k]
    for j = 1, n do e = e - X[k][j] * th[j] end
    sse, sst = sse + e * e, sst + (y[k] - mean) ^ 2
  end
  return th, 1 - sse / math.max(sst, 1e-12), sse
end

local function identifyLateral(D)
  local best
  for d = 0, 3 do
    local X, y = {}, {}
    for k = d + 1, #D - 1 do
      X[#X + 1] = { D[k].p, D[k].r, D[k].b, D[k - d].u, 1 }
      y[#y + 1] = D[k + 1].p - D[k].p
    end
    local th, r2, sse = fit(X, y)
    if th and (not best or sse < best.sse) then best = { d = d, p = th, r2p = r2, sse = sse } end
  end
  if not best then return nil end
  local d = best.d
  local Xr, yr, Xb, yb = {}, {}, {}, {}
  for k = d + 1, #D - 1 do
    local s, u = D[k], D[k - d].u
    Xr[#Xr + 1] = { s.p, s.r, s.b, u, 1 }
    yr[#yr + 1] = D[k + 1].r - s.r
    Xb[#Xb + 1] = { s.p, s.r, s.b, s.ph, u, 1 }
    yb[#yb + 1] = D[k + 1].b - s.b
  end
  local thr, r2r = fit(Xr, yr)
  local thb, r2b = fit(Xb, yb)
  if not thr or not thb then return nil end
  local h, v = 0, 0
  for _, s in ipairs(D) do h, v = h + s.dt, v + s.v end
  return { d = d, p = best.p, r = thr, b = thb, r2p = best.r2p, r2r = r2r, r2b = r2b, h = h / #D, V0 = v / #D }
end

-- Discrete model at airspeed V: surface and sideslip terms grow with speed (Sable forces ~ speed)
local function lateralSystem(M, V)
  local s = clamp(V / M.V0, 0.4, 2.5)
  local d = M.d
  local n = 5 + d
  local A, B = {}, {}
  for i = 1, n do
    A[i], B[i] = {}, 0
    for j = 1, n do A[i][j] = 0 end
  end
  local p, r, b = M.p, M.r, M.b
  A[1][1], A[1][2], A[1][3] = 1 + p[1], p[2], p[3] * s
  A[2][1], A[2][2], A[2][3] = r[1], 1 + r[2], r[3] * s
  A[3][1], A[3][2], A[3][3], A[3][4] = b[1], b[2], 1 + b[3], b[4] / s
  A[4][1], A[4][4] = M.h, 1
  A[5][4], A[5][5] = M.h, 1
  local gu = { p[4] * s, r[4] * s, b[5] }
  if d == 0 then
    B[1], B[2], B[3] = gu[1], gu[2], gu[3]
  else
    A[1][5 + d], A[2][5 + d], A[3][5 + d] = gu[1], gu[2], gu[3]
    B[6] = 1
    for i = 7, 5 + d do A[i][i - 1] = 1 end
  end
  return A, B, n
end

-- Discrete LQR for one input (Riccati iteration). Returns K (u = -K x) or nil.
local function dlqr(A, B, Q, R, n)
  local P = {}
  for i = 1, n do
    P[i] = {}
    for j = 1, n do P[i][j] = i == j and (Q[i] or 0) or 0 end
  end
  local K
  for _ = 1, 600 do
    local BP = {}
    for j = 1, n do
      local s = 0
      for i = 1, n do s = s + B[i] * P[i][j] end
      BP[j] = s
    end
    local den = R
    for i = 1, n do den = den + BP[i] * B[i] end
    K = {}
    for j = 1, n do
      local s = 0
      for i = 1, n do s = s + BP[i] * A[i][j] end
      K[j] = s / den
    end
    local PA = {}
    for i = 1, n do
      PA[i] = {}
      for j = 1, n do
        local s = 0
        for k = 1, n do s = s + P[i][k] * (A[k][j] - B[k] * K[j]) end
        PA[i][j] = s
      end
    end
    local diff = 0
    for i = 1, n do
      for j = 1, n do
        local s = i == j and (Q[i] or 0) or 0
        for k = 1, n do s = s + A[k][i] * PA[k][j] end
        diff = math.max(diff, abs(s - P[i][j]))
        P[i][j] = s
      end
    end
    if diff ~= diff or diff > 1e12 then return nil end
    if diff < 1e-7 * (1 + abs(P[1][1])) then break end
  end
  -- The closed loop on the model must settle a 0.3 rad bank error
  local x = {}
  for i = 1, n do x[i] = 0 end
  x[4] = 0.3
  for _ = 1, 400 do
    local u = 0
    for i = 1, n do u = u - K[i] * x[i] end
    local nx = {}
    for i = 1, n do
      local s = B[i] * u
      for j = 1, n do s = s + A[i][j] * x[j] end
      nx[i] = s
    end
    x = nx
  end
  if not (abs(x[4]) < 0.01) then return nil end
  return K
end

local function designLateral(V)
  local M = L.lat
  if not M then return false end
  local A, B, n = lateralSystem(M, V)
  local Q = { 1 / CFG.Q_P ^ 2, 1 / CFG.Q_R ^ 2, 1 / (CFG.Q_BETA * RAD) ^ 2, 1 / (CFG.Q_BANK * RAD) ^ 2, 1 / (CFG.Q_INT * RAD) ^ 2 }
  local K = dlqr(A, B, Q, 1 / CFG.LQR_U ^ 2, n)
  if not K then return false end
  G.latK, G.latN, G.latV = K, n, V
  return true
end

-- The aircraft flies the other way than assumed: turn the frame round, re-derive the surfaces
flipNose = function()
  G.F = mul(G.F, -1)
  computeMixing()
  loadState()
  G.latK = nil
  if L.lat then designLateral(L.lat.V0) end
  E.q = nil
  C.iroll, C.ivert, C.eint, C.uhist = 0, 0, 0, {}
  note("nose is %s: set fighter.forward %s", G.fwdName, G.fwdName)
end

---------------------------------------------------------------- surface check
local CHECK_STEPS = {
  { "settle", 1.0 }, { "lat", 7 * CFG.ID_UNIT + CFG.ID_FREE }, { "settle", 1.5 },
  { "vert+", CFG.CHECK_HALF }, { "vert-", CFG.CHECK_HALF }, { "settle", 0.6 },
}

-- 3-2-1-1 input (standard for identification), then free response
local function idInput(el)
  local u = CFG.ID_UNIT
  if el < 3 * u then return 1 elseif el < 5 * u then return -1
  elseif el < 6 * u then return 1 elseif el < 7 * u then return -1 end
  return 0
end

local function finishLateral(chk)
  local M = identifyLateral(chk.lat)
  local ok = M and M.r2p > 0.3
  if ok then
    local old = L.lat
    L.lat = M
    ok = designLateral(E.speed)
    if not ok then L.lat = old end
  end
  if ok then
    note("lateral model d=%d fit %.2f/%.2f", M.d, M.r2p, M.r2r)
  else
    note("lateral model failed (%s)", M and string.format("fit %.2f", M.r2p) or "no data")
  end
end

-- Returns roll and vertical offsets (deg) to add open-loop, or nil
local function checkStep(now, dt)
  local chk = S.chk
  local step = CHECK_STEPS[chk.step]
  local el = now - chk.t
  if el >= step[2] then
    chk.res[chk.step] = slope(chk.samples)
    if step[1] == "lat" then finishLateral(chk) end
    chk.samples, chk.step, chk.t, el = {}, chk.step + 1, now, 0
    if chk.step > #CHECK_STEPS then return nil, nil, true end
    step = CHECK_STEPS[chk.step]
  end
  local kind = step[1]
  if kind == "lat" then
    local u = CFG.ID_AMP * idInput(el)
    chk.lat[#chk.lat + 1] = { p = E.p, r = E.yr, b = E.beta, ph = E.bank, u = u, dt = dt, v = E.speed }
    return u, nil
  end
  if el > CFG.CHECK_SKIP and kind ~= "settle" then
    chk.samples[#chk.samples + 1] = { el, G.vmode == "lift" and E.vel.y or E.qr }
  end
  if kind == "vert+" then return nil, CFG.CHECK_VERT end
  if kind == "vert-" then return nil, -CFG.CHECK_VERT end
  return nil, nil
end

local function finishCheck()
  local res = S.chk.res
  local sched = (math.max(E.speed, 8) / CFG.V_REF) ^ CFG.EFF_EXP
  local msg = {}
  if res[4] and res[5] then
    local b = (res[4] - res[5]) / (2 * CFG.CHECK_VERT) / sched
    local floorB = G.vmode == "lift" and 0.01 or 0.002
    if abs(b) > floorB then
      if b < 0 then
        for _, s in ipairs(G.surf) do s.mv = -s.mv end
        L.vertSign = -L.vertSign
        msg[#msg + 1] = "vert FLIPPED"
      end
      if G.vmode == "lift" then
        L.kvert = clamp(CFG.W_VERT / abs(b), 0.2, 40)
        L.kivert = L.kvert * 0.35
        L.kbank = clamp(9.8 / abs(b) * 0.8, 0, CFG.FLAP_MAX)
      else
        L.kq = clamp(CFG.W_Q / abs(b), 2, 150)
      end
      msg[#msg + 1] = string.format("vert %.3f", abs(b))
    else msg[#msg + 1] = "vert ?" end
  end
  L.checked = true
  S.forceCheck = false
  C.gr, C.gv = 1, 1
  saveState()
  note("check: %s", table.concat(msg, ", "))
  S.chk = nil
end

---------------------------------------------------------------- control
-- Sign-flip counter: too many large swings in a few seconds -> lower that loop's gain
local function oscGuard(o, e, thresh, now)
  if abs(e) > thresh then
    local s = e > 0 and 1 or -1
    if o.s and s ~= o.s then o.flips[#o.flips + 1] = now end
    o.s = s
  end
  while o.flips[1] and now - o.flips[1] > 3 do table.remove(o.flips, 1) end
  if #o.flips >= 6 then
    o.flips = {}
    return 0.7
  end
  return nil
end
local OSC = { roll = { flips = {} }, vert = { flips = {} } }

local function sendSurfaces(dRoll, dVert)
  local M = CFG.FLAP_MAX
  dRoll = clamp(dRoll, -M, M)
  -- Vertical demand only as far as every surface stays inside +-M (roll has priority)
  local lo, hi = -1e9, 1e9
  for _, s in ipairs(G.surf) do
    if abs(s.mv) > 1e-6 then
      local a, b = (-M - s.mr * dRoll) / s.mv, (M - s.mr * dRoll) / s.mv
      if a > b then a, b = b, a end
      lo, hi = math.max(lo, a), math.min(hi, b)
    end
  end
  local sat = 0
  if lo > hi then dVert = 0
  elseif dVert < lo then dVert, sat = lo, -1
  elseif dVert > hi then dVert, sat = hi, 1 end
  for _, s in ipairs(G.surf) do
    local a = clamp(s.mr * dRoll + s.mv * dVert, -M, M)
    s.cmd = a
    if not S.dry and (not s.sent or abs(a - s.sent) > 0.1) then
      s.sent = a
      queue(function() s.set(a) end)
    end
  end
  return dVert, sat
end

local function sendThrottle(x)
  S.thr = x
  for _, t in ipairs(G.thr) do
    local lv = floor(x * t.steps + 0.5)
    if not S.dry and lv ~= t.sent then
      t.sent = lv
      if t.ashcc then queue(function() t.p.setThrust(lv / t.steps) end)
      else queue(function() t.p.setPower(lv) end) end
    end
  end
end

local function control(dt, now)
  local cmd = S.cmd
  local V = math.max(E.speed, 1)
  local sched = clamp((CFG.V_REF / math.max(V, 8)) ^ CFG.EFF_EXP, 0.25, 4)
  local gain = optNum("gain", 1)
  local flying = not S.ground and V > optNum("min_speed", 15) * 0.6
  local rollOff, vertOff, done = nil, nil, false
  if cmd.check and S.chk then rollOff, vertOff, done = checkStep(now, dt) end

  -- Roll: the bank command moves at a limited rate; the measured model (LQR) turns it into differential
  local step = CFG.BANK_RATE * RAD * dt
  S.bankRef = (S.bankRef or E.bank) + clamp(wrap(cmd.bank - (S.bankRef or E.bank)), -step, step)
  S.auth = 1
  local dRoll
  if rollOff then
    dRoll = C.iroll + rollOff
  elseif S.mode == "RECOVER" and S.spin then
    dRoll = C.iroll
  elseif G.latK then
    if abs(V - G.latV) > 0.15 * G.latV and now - (S.redesignT or 0) > 1 then
      S.redesignT = now
      designLateral(V)
    end
    local K = G.latK
    local eph = wrap(E.bank - S.bankRef)
    local rRef = -9.8 * math.tan(clamp(S.bankRef, -1.3, 1.3)) / V          -- yaw rate of a clean turn
    local x = { E.p, E.yr - rRef, E.beta, eph, C.eint }
    for i = 1, G.latN - 5 do x[5 + i] = C.uhist[i] or 0 end
    local u = 0
    for i = 1, G.latN do u = u - K[i] * x[i] end
    u = u * C.gr * gain
    local uc = clamp(u, -CFG.LAT_MAX, CFG.LAT_MAX)
    if flying and uc == u then C.eint = clamp(C.eint + dt * eph, -0.6, 0.6) end
    dRoll = C.iroll + uc
    local f = flying and oscGuard(OSC.roll, eph, 5 * RAD, now)
    if f then C.gr = math.max(C.gr * f, 0.25); note("roll gain x%.2f", C.gr) end
  else
    -- Until the model is measured: gentle bank hold, less authority while the nose slips sideways
    local eb = wrap(S.bankRef - E.bank)
    local pc = clamp(CFG.K_BANK * eb, -L.pmax, L.pmax)
    local auth = clamp(1 - (abs(E.beta) * DEG - CFG.SLIP_OK) / CFG.SLIP_SPAN, CFG.AUTH_MIN, 1)
    if abs(E.bank) > abs(S.bankRef) + 8 * RAD and eb * E.bank < 0 then auth = 1 end
    S.auth = auth
    local er = pc - E.p
    local lim = L.rollMax * auth
    dRoll = C.iroll + clamp(L.kroll * C.gr * gain * er * sched, -lim, lim)
    if flying and auth > 0.8 then
      C.iroll = clamp(C.iroll + CFG.KI_ROLL * eb * dt * sched, -CFG.I_ROLL, CFG.I_ROLL)
    end
  end
  table.insert(C.uhist, 1, dRoll - C.iroll)
  C.uhist[5] = nil

  -- Climb: climb-rate error -> collective (lift mode) or pitch attitude (pitch mode)
  local evy = cmd.vy - E.vel.y
  local dVert, er
  if G.vmode == "lift" then
    local cb = cos(clamp(E.bank, -1.3, 1.3))
    local ff = L.kbank * (1 / cb - 1) * sched
    er = evy
    dVert = L.kvert * C.gv * gain * evy * sched + C.ivert + ff
    if vertOff then dVert = C.ivert + ff + vertOff end
  else
    local thc = C.ith + clamp(CFG.K_THETA * evy, -0.35, 0.35)
    local turn = 9.8 * math.tan(clamp(E.bank, -1.2, 1.2)) / V
    local qc = clamp(CFG.K_PITCH * (thc - E.pitch), -CFG.Q_MAX, CFG.Q_MAX) + turn * sin(abs(E.bank))
    er = qc - E.qr
    dVert = L.kq * C.gv * gain * er * sched + C.ivert
    if vertOff then dVert = C.ivert + vertOff end
    if flying and not vertOff then
      C.ith = clamp(C.ith + CFG.KI_THETA * evy * dt, -0.3, 0.5)
      C.ivert = clamp(C.ivert + CFG.KI_Q * L.kq * er * dt * sched, -CFG.I_VERT, CFG.I_VERT)
    end
  end
  if S.mode == "TAKEOFF" then dVert = (G.vmode == "lift") and 8 or L.kq * (0.15 - E.pitch) end
  if S.mode == "RECOVER" and S.spin then dVert, vertOff = C.ivert, 0 end
  local sentVert, sat = sendSurfaces(dRoll, dVert)
  C.vsat = sat ~= 0
  if G.vmode == "lift" and flying and not vertOff then
    local di = L.kivert * evy * dt * sched
    if (sat > 0 and di > 0) or (sat < 0 and di < 0) then di = 0 end
    C.ivert = clamp(C.ivert + di, -CFG.I_VERT, CFG.I_VERT)
  end
  if flying and not vertOff then
    local f = oscGuard(OSC.vert, er, G.vmode == "lift" and 1.5 or 0.15, now)
    if f then C.gv = math.max(C.gv * f, 0.25); note("climb gain x%.2f", C.gv) end
  end
  S.dRoll, S.dVert = dRoll, sentVert

  -- Throttle: speed error with a slow trim
  local thr
  if cmd.thr then
    thr = cmd.thr
  else
    local es = cmd.speed - E.speed
    C.ithr = clamp(C.ithr + CFG.KI_SPD * es * dt, 0, 1)
    thr = clamp(C.ithr + CFG.K_SPD * es, 0, 1)
    if E.speed > optNum("max_speed", 80) then thr = 0 end
  end
  sendThrottle(thr)

  if done then finishCheck(); setMode("PATROL") end

  -- Guns
  local side = tostring(opt("fire_side") or "")
  local fire = cmd.fire and S.armed and side ~= "" and not S.dry
  if side ~= "" and fire ~= S.firing then
    S.firing = fire
    pcall(redstone.setOutput, side, fire)
  end
end

---------------------------------------------------------------- logging
local function logRow(now)
  if not opt("log") or S.dry then return end
  if now - (S.logT or 0) < 0.25 then return end
  S.logT = now
  local ok = pcall(function()
    if not S.logF then
      if fs.exists(LOG_FILE) and fs.getSize(LOG_FILE) > CFG.LOG_MAX then
        if fs.exists(LOG_FILE .. ".old") then fs.delete(LOG_FILE .. ".old") end
        fs.move(LOG_FILE, LOG_FILE .. ".old")
      end
      local fresh = not fs.exists(LOG_FILE)
      S.logF = fs.open(LOG_FILE, "a")
      if fresh then S.logF.writeLine("t,mode,x,y,z,spd,vy,vyc,bank,bankc,pitch,p,q,thr,droll,dvert,rng,aim,beta,yaw,auth") end
      S.logRows = 0
    end
    local cmd = S.cmd
    S.logF.writeLine(string.format("%.2f,%s,%.1f,%.1f,%.1f,%.2f,%.2f,%.2f,%.1f,%.1f,%.1f,%.3f,%.3f,%.2f,%.1f,%.1f,%s,%s,%.1f,%.2f,%.2f",
      now - S.t0, S.mode, E.pos.x, E.pos.y, E.pos.z, E.speed, E.vel.y, cmd.vy or 0, E.bank * DEG, (cmd.bank or 0) * DEG,
      E.pitch * DEG, E.p, E.qr, S.thr or 0, S.dRoll or 0, S.dVert or 0,
      S.tgtRange and string.format("%.0f", S.tgtRange) or "", S.aimErr and string.format("%.1f", S.aimErr * DEG) or "",
      E.beta * DEG, E.yr, S.auth or 1))
    S.logRows = S.logRows + 1
    if S.logRows % 40 == 0 then
      S.logF.close()
      S.logF = nil
    end
  end)
  if not ok then S.logF = nil end
end

---------------------------------------------------------------- loops
local function controlLoop()
  local last = os.clock()
  while true do
    local calls = { function() S.me = G.radar.getSelf() end }
    local now = os.clock()
    if T.cur then
      local id = T.cur.id
      calls[#calls + 1] = function()
        local c = G.radar.getContact(id)
        if c then T.c, T.cT = c, os.clock() end
      end
    end
    local mode = tostring(opt("targets") or "aircraft"):lower()
    if now - T.scanT >= CFG.SCAN_EVERY and mode ~= "none" then
      T.scanT = now
      local range = optNum("engage_range", 400) * 1.5
      calls[#calls + 1] = function()
        local list = G.radar.scanSubLevels(range) or {}
        if mode == "players" or mode == "all" then
          for _, p in ipairs(G.radar.scanPlayers(range) or {}) do list[#list + 1] = p end
        end
        local t = os.clock()
        for _, c in ipairs(list) do c.seen = t end
        T.list = list
        T.fresh = true
      end
    end
    for _, f in ipairs(S.pending) do calls[#calls + 1] = f end
    S.pending = {}
    parallel.waitForAll(table.unpack(calls))
    now = os.clock()
    local dt = clamp(now - last, 0.05, 0.5)
    last = now
    if not S.me then error("Lost the aircraft (radar.getSelf() is nil)", 0) end
    estimate(dt)
    if T.fresh then T.fresh = false; pickTarget() end
    if T.cur and now - (T.cT or 0) > CFG.LOST_AFTER then note("target lost"); T.cur, T.c = nil, nil end
    decide(dt)
    limits()
    control(dt, now)
    logRow(now)
  end
end

local function fmt(x, w, d)
  if not x then return string.rep(" ", w - 1) .. "-" end
  return string.format("%" .. w .. "." .. (d or 0) .. "f", x)
end

local function screenLines()
  local cmd = S.cmd
  local out = {}
  out[#out + 1] = string.format("AshCC fighter  %-8s guns:%s %s", S.mode,
    (tostring(opt("fire_side") or "") == "") and "none" or (S.armed and "ARMED" or "safe"), S.dry and "DRY" or "")
  if not E.pos then return out end
  out[#out + 1] = string.format("spd %s/%s thr %3d%%  alt %s/%s floor %s",
    fmt(E.speed, 5, 1), fmt(cmd.speed, 3), floor((S.thr or 0) * 100 + 0.5), fmt(E.pos.y, 4), fmt(S.patrolAlt, 4), fmt(S.floor, 4))
  out[#out + 1] = string.format("vy %s/%s  bank %s/%s  pitch %s",
    fmt(E.vel.y, 5, 1), fmt(cmd.vy, 5, 1), fmt(E.bank * DEG, 4), fmt((cmd.bank or 0) * DEG, 4), fmt(E.pitch * DEG, 4))
  local fl = {}
  for _, s in ipairs(G.surf) do
    if s.eff then fl[#fl + 1] = string.format("%s %+5.1f", s.label:gsub("flap_bearing_", "f"), s.cmd or 0) end
  end
  out[#out + 1] = "flaps " .. table.concat(fl, "  ")
  out[#out + 1] = string.format("ctl %s gains x%.2f/x%.2f %s", G.vmode, C.gr, C.gv, L.checked and "checked" or "unchecked")
  local nf = 0
  for _, seen in pairs(T.friends) do if os.clock() - seen < 10 then nf = nf + 1 end end
  out[#out + 1] = string.format("home %s  contacts %d  friends %d",
    fmt(len(v3(E.pos.x - S.home.x, 0, E.pos.z - S.home.z)), 5), #T.list, nf)
  if T.cur and T.c then
    local r = sub(vt(T.c), E.pos)
    out[#out + 1] = string.format("TGT %s  rng %s  v %s", tostring(T.cur.name):sub(1, 16), fmt(len(r), 4),
      fmt(contactSpeed(T.c), 4, 1))
    out[#out + 1] = string.format("    aim %s deg  closure %s %s", fmt(S.aimErr and S.aimErr * DEG, 5, 1),
      fmt(S.closure, 5, 1), S.firing and "FIRE" or "")
  else
    out[#out + 1] = "TGT none"
  end
  if S.threat then out[#out + 1] = "THREAT " .. tostring(S.threat.name or S.threat.id):sub(1, 30) end
  for _, n in ipairs(S.notes) do out[#out + 1] = n end
  out[#out + 1] = "q quit a arm h home p patrol t target c check"
  out[#out + 1] = "up/down patrol alt +-10"
  return out
end

local function draw(dev)
  local w, h = dev.getSize()
  local lines = screenLines()
  for i = 1, h do
    dev.setCursorPos(1, i)
    dev.clearLine()
    if lines[i] then dev.write(lines[i]:sub(1, w)) end
  end
end

local function uiLoop()
  if G.monitor then pcall(G.monitor.setTextScale, 0.5) end
  while true do
    draw(term)
    if G.monitor then pcall(draw, G.monitor) end
    sleep(0.25)
  end
end

local function inputLoop()
  while true do
    local _, key = os.pullEvent("key")
    if key == keys.q then return
    elseif key == keys.a then S.armed = not S.armed; note("guns %s", S.armed and "armed" or "safe")
    elseif key == keys.h and E.pos then
      S.home, S.patrolAlt = v3(E.pos.x, E.pos.y, E.pos.z), E.pos.y
      note("home set")
    elseif key == keys.p then
      T.cur, T.c = nil, nil
      S.disengage = os.clock() + 15
      note("patrol for 15 s")
    elseif key == keys.t then
      local list, idx = {}, 1
      for _, c in ipairs(T.list) do if canAttack(c) then list[#list + 1] = c end end
      for i, c in ipairs(list) do if T.cur and c.id == T.cur.id then idx = i + 1 end end
      local c = list[idx] or list[1]
      if c then
        T.cur, T.c, T.cT = { id = c.id, name = c.name or c.type or "?", kind = c.kind }, c, os.clock()
        T.validT, S.disengage = os.clock(), 0
        note("target %s", T.cur.name)
      end
    elseif key == keys.c then S.forceCheck = true; note("check requested")
    elseif key == keys.up then S.patrolAlt = S.patrolAlt + 10
    elseif key == keys.down then S.patrolAlt = math.max(S.patrolAlt - 10, S.floor + 10)
    end
  end
end

local function modemLoop()
  if not G.modem then while true do os.pullEvent("fighter_never") end end
  local ch = optNum("channel", 4471)
  G.modem.open(ch)
  local timer = os.startTimer(0.5)
  while true do
    local ev = { os.pullEvent() }
    if ev[1] == "timer" and ev[2] == timer then
      if E.pos then
        pcall(G.modem.transmit, ch, ch, { ashccFighter = 1, team = opt("team"), sub = E.selfId,
          x = E.pos.x, y = E.pos.y, z = E.pos.z })
      end
      timer = os.startTimer(2)
    elseif ev[1] == "modem_message" and ev[3] == ch and type(ev[5]) == "table" and ev[5].ashccFighter then
      local m = ev[5]
      if m.team == opt("team") and m.sub then T.friends[m.sub] = os.clock() end
    end
  end
end

---------------------------------------------------------------- start / stop
local function prepareSurfaces()
  for _, f in ipairs(G.flaps) do
    local p = f.p
    if f.quad then
      local n, why = p.assemble()
      if type(why) == "table" then for side, r in pairs(why) do print(f.name .. ":" .. side .. " " .. tostring(r)) end end
    elseif not p.isAssembled() then
      local ok, why = p.assemble()
      if not ok then print(f.name .. " assemble failed: " .. tostring(why)) end
    end
    pcall(p.setSpeed, CFG.FLAP_SPEED)
  end
end

local function stopAll()
  if S.dry or not S.started then return end
  local calls = {}
  for _, s in ipairs(G.surf) do calls[#calls + 1] = function() pcall(s.set, 0) end end
  for _, t in ipairs(G.thr) do
    calls[#calls + 1] = function()
      if t.ashcc then pcall(t.p.setThrust, 0) else pcall(t.p.setPower, 0) end
    end
  end
  local side = tostring(opt("fire_side") or "")
  if side ~= "" then calls[#calls + 1] = function() pcall(redstone.setOutput, side, false) end end
  if #calls > 0 then parallel.waitForAll(table.unpack(calls)) end
end

local function printHelp()
  print("fighter [run|check|info|dry|help]")
  for _, s in ipairs(SETTINGS) do
    print(string.format(" fighter.%s = %s", s[1], tostring(opt(s[1]))))
  end
  print("change: set fighter.<name> <value>")
end

local function initFlight(me)
  S.dry = CMD == "dry"
  S.forceCheck = CMD == "check"
  S.armed = tostring(opt("fire_side") or "") ~= ""
  S.startY = me.y
  local spd = sqrt((me.vx or 0) ^ 2 + (me.vy or 0) ^ 2 + (me.vz or 0) ^ 2)
  S.ground = spd < 2 and abs(me.vy or 0) < 0.5
  local floorOpt = tonumber(opt("floor"))
  S.floor = floorOpt or (S.ground and me.y + 12 or me.y - 20)
  S.patrolAlt = tonumber(opt("patrol_alt")) or S.floor + 40
  S.patrolR = optNum("patrol_radius", 120)
  S.home = v3(me.x, me.y, me.z)
  S.cmd = { bank = 0, vy = 0, speed = optNum("cruise_speed", 30) }
  C.ithr = S.ground and 1 or 0.6
end

-- fighter probe [throttle 0-1] [seconds]: flaps neutral, fixed throttle, record the free flight (no control)
local function probe()
  local thr, secs = tonumber(args[2]) or 0.6, tonumber(args[3]) or 15
  local file = "fighter_probe.csv"
  local f = fs.open(file, "w")
  f.writeLine("t,x,y,z,vx,vy,vz,qw,qx,qy,qz,spd,bank,pitch,beta,p,q,r")
  S.started = true
  prepareSurfaces()
  local calls = {}
  for _, s in ipairs(G.surf) do calls[#calls + 1] = function() s.set(0) end end
  for _, t in ipairs(G.thr) do
    calls[#calls + 1] = function()
      if t.ashcc then t.p.setThrust(thr) else t.p.setPower(floor(thr * 15 + 0.5)) end
    end
  end
  parallel.waitForAll(table.unpack(calls))
  local t0, last = os.clock(), os.clock()
  print(string.format("probe: throttle %.2f for %ds -> %s (Ctrl+T stops)", thr, secs, file))
  while os.clock() - t0 < secs do
    S.me = G.radar.getSelf()
    local now = os.clock()
    local dt = clamp(now - last, 0.05, 0.5)
    last = now
    if not S.me then break end
    estimate(dt)
    local q = E.q
    f.writeLine(string.format("%.2f,%.2f,%.2f,%.2f,%.3f,%.3f,%.3f,%.5f,%.5f,%.5f,%.5f,%.2f,%.1f,%.1f,%.1f,%.3f,%.3f,%.3f",
      now - t0, E.pos.x, E.pos.y, E.pos.z, E.vel.x, E.vel.y, E.vel.z, q.w, q.x, q.y, q.z, E.speed,
      E.bank * DEG, E.pitch * DEG, E.beta * DEG, E.p, E.qr, E.yr))
  end
  f.close()
  print("probe done: " .. file)
end

local function main()
  if CMD == "help" then printHelp() return end
  discover()
  local me = setupGeometry()
  loadState()
  if L.lat then designLateral(L.lat.V0) end
  if CMD == "info" then
    geometryReport(print)
    return
  end
  if CMD == "probe" then
    geometryReport(print)
    probe()
    return
  end
  if not G.canRoll and not G.canVert then
    geometryReport(print)
    error("No usable control surfaces: link the flaps with a link hub (positions) or set fighter.roles", 0)
  end
  term.clear()
  term.setCursorPos(1, 1)
  geometryReport(print)
  if CMD ~= "dry" then
    S.started = true
    prepareSurfaces()
  end
  initFlight(me)
  note("start %s%s", S.ground and "on ground" or "in the air", S.dry and " (dry)" or "")
  sleep(1)
  parallel.waitForAny(controlLoop, uiLoop, inputLoop, modemLoop)
end

local ok, err = pcall(main)
pcall(stopAll)
if S.logF then pcall(S.logF.close) end
if CMD ~= "info" and CMD ~= "help" then
  term.clear()
  term.setCursorPos(1, 1)
end
if not ok then
  printError(err)
  for _, n in ipairs(S.notes) do print(n) end
else
  print("fighter stopped (controls neutral, throttle off)")
end
