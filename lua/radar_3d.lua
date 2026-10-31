-- 3D radar: terrain (radar.scanTerrain) as a shaded 3D map on an HD monitor, with ships and players over it.
-- Usage: radar_3d [range] [cells]     (q quits)
--   drag (right-click and move): rotate / tilt the view     scroll while holding: zoom
--   buttons (top right): range -/+, vertical scale -/+, north-up / heading-up, colours, reset view
-- Each contact has a stalk down to the ground under it; green = same IFF code as us, red = other ships, cyan = players.
local radar = peripheral.find("radar") or error("No radar attached", 0)
local mon = peripheral.find("hd_monitor") or error("No hd_monitor attached", 0)
local args = { ... }

local maxRange = radar.getRange()
local range = tonumber(args[1]) or math.min(maxRange, 256)
local cells = math.max(8, math.min(tonumber(args[2]) or 40, 96))
local W, H = mon.getResolution()
local TS = math.max(1, math.floor(math.min(W, H) / 384)) -- text scale
local FONT = 16 * TS

local view = { yaw = 0, pitch = 35, zoom = 1, vmul = 1, northUp = false, mapColors = true }
local terrain, contacts, players, myIff = nil, {}, {}, nil
local dirty, terrainDirty = true, true

---------------------------------------------------------------- maths
local function clamp(v, lo, hi) return math.max(lo, math.min(hi, v)) end
local function norm(x, y, z)
  local l = math.sqrt(x * x + y * y + z * z)
  if l < 1e-9 then return 0, 0, 0 end
  return x / l, y / l, z / l
end
local function shade(c, k)
  local r, g, b = math.floor(c / 65536) % 256, math.floor(c / 256) % 256, c % 256
  return math.floor(clamp(r * k, 0, 255)) * 65536 + math.floor(clamp(g * k, 0, 255)) * 256 + math.floor(clamp(b * k, 0, 255))
end
local function mix(a, b, t)
  local function ch(c, s) return math.floor(c / s) % 256 end
  local function m(s) return math.floor(ch(a, s) + (ch(b, s) - ch(a, s)) * t + 0.5) end
  return m(65536) * 65536 + m(256) * 256 + m(1)
end

-- Height colours (relative to the radar) for the "height" colour mode
local RAMP = { { -96, 0x1E3A5F }, { -48, 0x2E6B3A }, { -16, 0x4C9A3C }, { 0, 0xA8A048 }, { 24, 0x9A7A5A }, { 64, 0xF0F0F0 } }
local function heightColor(dy)
  if dy <= RAMP[1][1] then return RAMP[1][2] end
  for i = 2, #RAMP do
    if dy <= RAMP[i][1] then return mix(RAMP[i - 1][2], RAMP[i][2], (dy - RAMP[i - 1][1]) / (RAMP[i][1] - RAMP[i - 1][1])) end
  end
  return RAMP[#RAMP][2]
end

---------------------------------------------------------------- camera
-- Scene units: horizontal = blocks / range (the map is -1..1), vertical = blocks / range * exaggeration.
-- x = right, y = up, z = forward (top of a 2D scope). The camera orbits the radar.
local cam = {}
local function setupCamera()
  local yaw, pitch = math.rad(view.yaw), math.rad(view.pitch)
  local dist = 2.8 / view.zoom
  cam.x = -math.sin(yaw) * math.cos(pitch) * dist
  cam.y = math.sin(pitch) * dist
  cam.z = -math.cos(yaw) * math.cos(pitch) * dist
  local fx, fy, fz = norm(-cam.x, -cam.y, -cam.z)
  local rx, ry, rz = norm(fz, 0, -fx) -- up x forward
  cam.f = { fx, fy, fz }
  cam.r = { rx, ry, rz }
  cam.u = { fy * rz - fz * ry, fz * rx - fx * rz, fx * ry - fy * rx } -- forward x right
  cam.focal = math.min(W, H) * 1.25
  cam.cx, cam.cy = W / 2, H / 2 + H * 0.05
end

-- Returns screen x, y and depth (nil when behind the camera)
local function project(x, y, z)
  local dx, dy, dz = x - cam.x, y - cam.y, z - cam.z
  local d = dx * cam.f[1] + dy * cam.f[2] + dz * cam.f[3]
  if d < 0.05 then return nil end
  local sx = dx * cam.r[1] + dy * cam.r[2] + dz * cam.r[3]
  local sy = dx * cam.u[1] + dy * cam.u[2] + dz * cam.u[3]
  return cam.cx + sx / d * cam.focal, cam.cy - sy / d * cam.focal, d
end

---------------------------------------------------------------- terrain geometry
local vex = 1 -- current vertical exaggeration (auto x manual)

local function sceneY(blocksAbove) return blocksAbove * vex / range end

-- World position -> map coordinates (blocks right / forward of the radar), using the terrain grid's axes
local function toMap(x, z)
  local t = terrain
  local dx, dz = x - t.originX, z - t.originZ
  return dx * t.right.x + dz * t.right.z, dx * t.forward.x + dz * t.forward.z
end

-- Ground height (world Y) under map coordinates, or nil
local function groundAt(lx, lz)
  local t = terrain
  if not t then return nil end
  local col = math.floor((lx + t.range) / t.cell) + 1
  local row = math.floor((t.range - lz) / t.cell) + 1
  if row < 1 or row > t.size or col < 1 or col > t.size then return nil end
  return t.height[row][col]
end

local LIGHT = { norm(-0.45, 1, 0.35) }

local function drawTerrain()
  local t = terrain
  if not t then return end
  local n, cell = t.size, t.cell / range
  -- Project every vertex (cell centres)
  local px, py, pd = {}, {}, {}
  for row = 1, n do
    local z = (t.range - (row - 0.5) * t.cell) / range
    local hr = t.height[row]
    for col = 1, n do
      local h = hr[col]
      local i = (row - 1) * n + col
      if h then
        local x = (-t.range + (col - 0.5) * t.cell) / range
        px[i], py[i], pd[i] = project(x, sceneY(h - t.originY), z)
      end
    end
  end
  -- Quads between neighbouring centres, far to near
  local quads = {}
  for row = 1, n - 1 do
    for col = 1, n - 1 do
      local a, b = (row - 1) * n + col, (row - 1) * n + col + 1
      local c, d = row * n + col + 1, row * n + col
      if pd[a] and pd[b] and pd[c] and pd[d] then
        quads[#quads + 1] = { (pd[a] + pd[b] + pd[c] + pd[d]) / 4, row, col }
      end
    end
  end
  table.sort(quads, function(p, q) return p[1] > q[1] end)

  for _, q in ipairs(quads) do
    local row, col = q[2], q[3]
    local a, b = (row - 1) * n + col, (row - 1) * n + col + 1
    local c, d = row * n + col + 1, row * n + col
    local h00, h01 = t.height[row][col], t.height[row][col + 1]
    local h11, h10 = t.height[row + 1][col + 1], t.height[row + 1][col]
    -- Normal from the slopes (row grows towards the viewer: -z)
    local sx = sceneY(((h01 + h11) - (h00 + h10)) / 2) / cell
    local sz = sceneY(((h00 + h01) - (h10 + h11)) / 2) / cell
    local nx, ny, nz = norm(-sx, 1, -sz)
    local light = 0.35 + 0.75 * math.max(0, nx * LIGHT[1] + ny * LIGHT[2] + nz * LIGHT[3])
    local kind = t.kind[row][col]
    local base
    if kind == "water" then
      base = view.mapColors and (t.color[row][col] or 0x3F76E4) or 0x2A5FB0
      light = 0.9 -- water is flat; keep it even
    elseif view.mapColors then
      base = t.color[row][col] or 0x707070
    else
      base = heightColor((h00 + h01 + h10 + h11) / 4 - t.originY)
    end
    local avg = (h00 + h01 + h10 + h11) / 4
    local col32 = shade(base, light)
    if avg > t.originY then col32 = mix(col32, 0xFF4040, 0.35) end -- above the radar: obstacle
    mon.fillPolygon({ px[a], py[a], px[b], py[b], px[c], py[c], px[d], py[d] }, col32)
  end
end

---------------------------------------------------------------- overlays
local function ring(radius, color)
  local pts = {}
  for i = 0, 63 do
    local a = i / 64 * 2 * math.pi
    local x, y = project(math.sin(a) * radius, 0, math.cos(a) * radius)
    if not x then return end
    pts[#pts + 1] = x
    pts[#pts + 1] = y
  end
  mon.drawPolygon(pts, color, TS)
end

local function line3(x1, y1, z1, x2, y2, z2, color, thick)
  local ax, ay = project(x1, y1, z1)
  local bx, by = project(x2, y2, z2)
  if ax and bx then mon.drawLine(ax, ay, bx, by, color, thick or TS) end
end

local function drawGrid()
  ring(1, "#60ff9080")
  ring(0.5, "#60ff9050")
  line3(-1, 0, 0, 1, 0, 0, "#60ff9040")
  line3(0, 0, -1, 0, 0, 1, "#60ff9040")
  -- North marker on the outer ring
  local t = terrain
  if t then
    local nx, nz = -t.right.z, -t.forward.z
    local x, y = project(nx * 1.08, 0, nz * 1.08)
    if x then mon.drawText(x, y - FONT / 2, "N", 0xFFFFFF, { scale = TS, align = "center" }) end
  end
end

local function drawSelf()
  local t = terrain
  local fx, fz = 0, 1 -- radar heading in map coordinates
  if t then
    local o = radar.getOrientation().forward
    local hx, _, hz = norm(o.x * t.right.x + o.z * t.right.z, 0, o.x * t.forward.x + o.z * t.forward.z)
    if hx ~= 0 or hz ~= 0 then fx, fz = hx, hz end
  end
  local s = 0.05
  local pts = {}
  for _, p in ipairs({ { 0, 1.4 }, { 0.8, -0.8 }, { 0, -0.3 }, { -0.8, -0.8 } }) do
    -- p = { right, forward } in the arrow's own frame
    local x = (p[2] * fx + p[1] * fz) * s
    local z = (p[2] * fz - p[1] * fx) * s
    local sx, sy = project(x, 0, z)
    if not sx then return end
    pts[#pts + 1] = sx
    pts[#pts + 1] = sy
  end
  mon.fillPolygon(pts, 0xFFFFFF)
end

local function drawContact(c, color, label)
  local t = terrain
  local lx, lz = toMap(c.x, c.z)
  if math.abs(lx) > range or math.abs(lz) > range then return end
  local x, z = lx / range, lz / range
  local y = sceneY(c.y - t.originY)
  local g = groundAt(lx, lz)
  local gy = g and sceneY(g + 1 - t.originY) or 0
  local px, py = project(x, y, z)
  if not px then return end
  line3(x, gy, z, x, y, z, color, TS)
  local gx, gyy = project(x, gy, z)
  if gx then mon.fillEllipse(gx, gyy, 4 * TS, 2 * TS, color) end
  mon.fillCircle(px, py, 5 * TS, color)
  mon.drawCircle(px, py, 7 * TS, 0xFFFFFF, 1)
  if label then
    mon.drawText(px + 9 * TS, py - FONT, label, color, { scale = TS })
  end
end

local function drawContacts()
  if not terrain then return end
  -- far to near so the near labels stay on top
  local list = {}
  for _, c in ipairs(contacts) do list[#list + 1] = c end
  for _, p in ipairs(players) do list[#list + 1] = p end
  local function depth(c)
    local lx, lz = toMap(c.x, c.z)
    local _, _, d = project(lx / range, sceneY(c.y - terrain.originY), lz / range)
    return d or -1
  end
  for _, c in ipairs(list) do c._d = depth(c) end
  table.sort(list, function(a, b) return a._d > b._d end)
  for _, c in ipairs(list) do
    local dy = c.y - terrain.originY
    if c.kind == "entity" then
      drawContact(c, 0x40E0FF, c.name)
    else
      local friendly = myIff and c.iff and c.iff.code == myIff
      local name = (c.iff and c.iff.callsign) or c.name or c.id:sub(1, 6)
      drawContact(c, friendly and 0x50FF70 or 0xFF5050,
        ("%s %dm %s%d"):format(name, math.floor(c.distance + 0.5), dy >= 0 and "+" or "", math.floor(dy + 0.5)))
    end
  end
end

---------------------------------------------------------------- buttons and HUD
local buttons = {}
local function layoutButtons()
  buttons = {}
  local labels = {
    { "R-", function() range = math.max(16, range / 2); terrainDirty = true end },
    { "R+", function() range = math.min(maxRange, range * 2); terrainDirty = true end },
    { "V-", function() view.vmul = math.max(0.25, view.vmul / 1.5) end },
    { "V+", function() view.vmul = math.min(16, view.vmul * 1.5) end },
    { "N/H", function() view.northUp = not view.northUp; terrainDirty = true end },
    { "CLR", function() view.mapColors = not view.mapColors end },
    { "RST", function() view.yaw, view.pitch, view.zoom, view.vmul = 0, 35, 1, 1 end },
  }
  local bw, bh, gap = 44 * TS, 24 * TS, 6 * TS
  local x = W - (#labels * (bw + gap))
  for _, l in ipairs(labels) do
    buttons[#buttons + 1] = { x = x, y = gap, w = bw, h = bh, label = l[1], fn = l[2] }
    x = x + bw + gap
  end
end

local function drawHud()
  for _, b in ipairs(buttons) do
    mon.fillRect(b.x, b.y, b.w, b.h, "#203828d0")
    mon.drawRect(b.x, b.y, b.w, b.h, 0x60FF90, 1)
    mon.drawText(b.x + b.w / 2, b.y + (b.h - FONT) / 2, b.label, 0xC0FFD0, { scale = TS, align = "center" })
  end
  local ships = #contacts
  mon.drawText(8 * TS, 8 * TS, ("3D RADAR  R=%d m  ships %d  players %d"):format(range, ships, #players), 0x9FFFB0, { scale = TS })
  local t = terrain
  local info = ("V x%.1f  %s  %s"):format(vex, view.northUp and "north-up" or "heading-up", view.mapColors and "map colours" or "height colours")
  if t and t.min then info = info .. ("  ground %d..%d (radar y %d)"):format(t.min, t.max, math.floor(t.originY)) end
  mon.drawText(8 * TS, 8 * TS + FONT + 4, info, 0x70C080, { scale = TS })
  mon.drawText(8 * TS, H - FONT - 6 * TS, "drag: rotate   scroll: zoom", 0x508060, { scale = TS })
end

---------------------------------------------------------------- frame
local function render()
  local t = terrain
  -- Auto exaggeration: make the relief about a third of the radius, then the manual factor
  local relief = 1
  if t and t.min then relief = math.max(t.max - t.min, math.abs(t.originY - t.min), 1) end
  vex = clamp(0.3 * range / relief, 1, 8) * view.vmul
  setupCamera()
  mon.fill(0x050B08)
  drawTerrain()
  drawGrid()
  drawSelf()
  drawContacts()
  drawHud()
  mon.present()
end

---------------------------------------------------------------- loops
local function scanner()
  local lastTerrain = -math.huge
  while true do
    if terrainDirty or os.clock() - lastTerrain >= 3 then
      terrain = radar.scanTerrain(range, cells, view.northUp)
      lastTerrain, terrainDirty = os.clock(), false
    end
    local r = radar.scan(range)
    contacts = r.subLevels
    players = {}
    for _, e in ipairs(r.entities) do if e.isPlayer then players[#players + 1] = e end end
    local self = radar.getSelf()
    myIff = self and self.iff and self.iff.code or nil
    dirty = true
    -- wake up early when a button changed the range or orientation
    local timer = os.startTimer(1)
    repeat
      local ev, id = os.pullEvent()
    until (ev == "timer" and id == timer) or (ev == "radar3d_rescan")
  end
end

local function input()
  local drag
  while true do
    local ev, p1, x, y, b = os.pullEvent()
    if ev == "hd_touch" then
      local hit
      for _, btn in ipairs(buttons) do
        if x >= btn.x and x < btn.x + btn.w and y >= btn.y and y < btn.y + btn.h then hit = btn end
      end
      if hit then
        hit.fn()
        if terrainDirty then os.queueEvent("radar3d_rescan") end
        dirty = true
      else
        drag = { x = x, y = y, yaw = view.yaw, pitch = view.pitch }
      end
    elseif ev == "hd_drag" and drag then
      view.yaw = (drag.yaw - (x - drag.x) * 0.35) % 360
      view.pitch = clamp(drag.pitch + (y - drag.y) * 0.25, 5, 89)
      dirty = true
    elseif ev == "hd_release" then
      drag = nil
    elseif ev == "hd_scroll" then
      view.zoom = clamp(view.zoom * (b < 0 and 1.15 or 1 / 1.15), 0.4, 6)
      dirty = true
    elseif ev == "key" and p1 == keys.q then
      return
    end
  end
end

local function renderer()
  while true do
    if dirty then
      dirty = false
      render()
    end
    sleep(0.1)
  end
end

mon.setAutoPresent(false)
layoutButtons()
parallel.waitForAny(scanner, input, renderer)
mon.setAutoPresent(true)
