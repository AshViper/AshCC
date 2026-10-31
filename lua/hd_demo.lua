-- HD monitor demo: radar scope + touch paint. Run: hd_demo [radar|paint|term]
-- radar : animated scope (with an AshCC radar attached it shows real contacts)
-- paint : draw with right-click drag, shift+right-click erases, scroll changes the brush
-- term  : redirects the shell to the HD monitor (CC programs work unchanged)
local mon = peripheral.find("hd_monitor") or error("No hd_monitor attached", 0)
local mode = ... or "radar"
local W, H = mon.getResolution()

local function radar()
  local radar = peripheral.find("radar")
  local cx, cy = W / 2, H / 2
  local R = math.min(W, H) / 2 - 20
  mon.setAutoPresent(false) -- whole frames only, no flicker
  local angle = 0
  -- Terrain under the scope: map colours, darker the lower they are than the radar, refreshed every 2 s
  local terrainImg, terrainN, terrainAt = nil, 0, -math.huge
  local function terrainImage()
    local t = radar.scanTerrain(radar.getRange(), 96)
    local px = {}
    for row = 1, t.size do
      for col = 1, t.size do
        local c, ht = t.color[row][col], t.height[row][col]
        local r, g, b = 0, 0, 0
        if c and ht then
          local dy = ht - t.originY
          local k = dy > 0 and 1 or math.max(0.25, 0.7 + dy / 160)
          r, g, b = math.floor(bit32.rshift(c, 16) % 256 * k), math.floor(bit32.rshift(c, 8) % 256 * k), math.floor(c % 256 * k)
        end
        px[#px + 1] = string.char(r, g, b)
      end
    end
    return table.concat(px), t.size
  end
  while true do
    mon.fill(0x06120A)
    if radar and os.clock() - terrainAt >= 2 then
      terrainImg, terrainN = terrainImage()
      terrainAt = os.clock()
    end
    if terrainImg then
      mon.drawImage(math.floor(cx - R), math.floor(cy - R), terrainN, terrainN, terrainImg,
        { format = "rgb", scaleWidth = math.floor(2 * R), scaleHeight = math.floor(2 * R) })
    end
    for i = 1, 4 do mon.drawCircle(cx, cy, R * i / 4, "#2fbf5a80", 1.5) end
    mon.drawLine(cx - R, cy, cx + R, cy, "#2fbf5a50")
    mon.drawLine(cx, cy - R, cx, cy + R, "#2fbf5a50")
    -- Sweep with a fading trail
    for k = 0, 20 do
      local a = math.rad(angle - k * 2)
      local alpha = math.floor(200 * (1 - k / 20))
      mon.drawLine(cx, cy, cx + R * math.sin(a), cy - R * math.cos(a), mon.rgba(80, 255, 120, alpha), 2)
    end
    local range = radar and radar.getRange() or 2048
    local contacts = radar and radar.scan().subLevels or {}
    for _, c in ipairs(contacts) do
      local d = math.min(1, c.distance / range)
      local a = math.rad(c.azimuth)
      local x, y = cx + R * d * math.sin(a), cy - R * d * math.cos(a)
      mon.fillCircle(x, y, 5, 0xFF5050)
      mon.drawText(x + 8, y - 8, c.name or "?", 0xFFD0D0)
    end
    mon.drawText(10, 10, ("RADAR  %d m  %d contacts"):format(range, #contacts), 0x9FFFB0, { scale = 2 })
    mon.drawText(W - 10, H - 26, os.date("%H:%M:%S"), 0x9FFFB0, { align = "right" })
    mon.present()
    angle = (angle + 6) % 360
    sleep(0.05)
  end
end

local function paint()
  local brush, colour = 4, 0xFFFFFF
  local palette = { 0xFFFFFF, 0xFF4040, 0xFFB030, 0xFFFF40, 0x40FF60, 0x40C0FF, 0x8060FF, 0xFF60C0 }
  local function ui()
    mon.fillRect(0, 0, W, 36, 0x202020)
    for i, c in ipairs(palette) do
      mon.fillRect(8 + (i - 1) * 40, 6, 32, 24, c)
      if c == colour then mon.drawRect(6 + (i - 1) * 40, 4, 36, 28, 0xFFFFFF, 2) end
    end
    mon.fillRect(W - 180, 6, 172, 24, 0x202020)
    mon.drawText(W - 10, 10, ("brush %d  (scroll)"):format(brush), 0xFFFFFF, { align = "right" })
  end
  mon.fill(0x101010)
  ui()
  local lastX, lastY
  while true do
    local ev, _, x, y, button = os.pullEvent()
    if ev == "hd_touch" and y < 36 then
      local i = math.floor((x - 8) / 40) + 1
      if palette[i] then colour = palette[i]; ui() end
    elseif ev == "hd_touch" or ev == "hd_drag" then
      local c = button == 2 and 0x101010 or colour
      if ev == "hd_drag" and lastX and y >= 36 then
        mon.drawLine(lastX, lastY, x, y, c, brush * 2)
      elseif y >= 36 then
        mon.fillCircle(x, y, brush, c)
      end
      lastX, lastY = x, y
    elseif ev == "hd_release" then
      lastX, lastY = nil, nil
    elseif ev == "hd_scroll" then
      brush = math.max(1, math.min(40, brush - button))
      ui()
    end
  end
end

local function termMode()
  mon.setTextScale(0.5)
  mon.setBackgroundColor(colors.black)
  mon.clear()
  mon.setCursorPos(1, 1)
  local old = term.redirect(mon)
  print("HD monitor: " .. W .. "x" .. H .. " px")
  shell.run("shell")
  term.redirect(old)
end

if mode == "radar" then radar()
elseif mode == "paint" then paint()
elseif mode == "term" then termMode()
else error("Usage: hd_demo [radar|paint|term]", 0) end
