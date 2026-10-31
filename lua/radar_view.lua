-- Radar scope: draws contacts around the radar on a monitor (or the terminal).
-- Usage: radar_view [range]
local radar = peripheral.find("radar") or error("No radar attached", 0)
local mon = peripheral.find("monitor")
local out = mon or term
if mon then mon.setTextScale(0.5) end

local range = tonumber(({...})[1]) or radar.getRange()
radar.setRange(range)

-- Terrain colour by kind and height relative to the radar (white = above the radar, an obstacle)
local function terrainColor(kind, dy)
  if kind == "water" then return dy < -20 and colors.blue or colors.cyan end
  if kind == "lava" then return colors.red end
  if kind == "unloaded" or kind == "void" then return colors.black end
  if dy > 0 then return colors.white end
  if kind == "snow" or kind == "ice" then return colors.lightGray end
  if dy > -8 then return colors.lime end
  if dy > -24 then return colors.green end
  if dy > -48 then return colors.brown end
  return colors.gray
end

local terrain, terrainAt = nil, 0

local function draw(result)
  local w, h = out.getSize()
  local cx, cy = math.floor(w / 2), math.floor(h / 2)
  local scale = math.min(cx, cy) / range
  out.setBackgroundColor(colors.black)
  out.clear()

  -- Terrain background, refreshed every 2 seconds
  if os.clock() - terrainAt >= 2 then
    terrain, terrainAt = radar.scanTerrain(range, 64), os.clock()
  end
  local n, cell = terrain.size, terrain.cell
  for sy = 1, h do
    local lz = (cy - sy) / scale
    local row = math.floor((range - lz) / cell) + 1
    if row >= 1 and row <= n then
      local line = {}
      for sx = 1, w do
        local lx = (sx - cx) / (scale * 2)
        local col = math.floor((lx + range) / cell) + 1
        local c = colors.black
        if col >= 1 and col <= n then
          local ht = terrain.height[row][col]
          c = terrainColor(terrain.kind[row][col], ht and ht - terrain.originY or 0)
        end
        line[sx] = colors.toBlit(c)
      end
      local bg = table.concat(line)
      out.setCursorPos(1, sy)
      out.blit((" "):rep(w), bg, bg)
    end
  end

  -- Up on screen = radar forward, right on screen = radar right
  local function plot(c, ch, col)
    local sx = cx + math.floor(c["local"].x * scale * 2 + 0.5) -- characters are ~2x taller than wide
    local sy = cy - math.floor(c["local"].z * scale + 0.5)
    if sx >= 1 and sx <= w and sy >= 1 and sy <= h then
      out.setCursorPos(sx, sy)
      out.setTextColor(col)
      out.write(ch)
    end
  end

  for _, s in ipairs(result.subLevels) do plot(s, "#", colors.orange) end
  for _, e in ipairs(result.entities) do
    if e.isPlayer then plot(e, "P", colors.lime)
    elseif e.isLiving then plot(e, "m", colors.red)
    else plot(e, ".", colors.gray) end
  end
  out.setCursorPos(cx, cy)
  out.setTextColor(colors.white)
  out.write("+")

  out.setCursorPos(1, 1)
  out.write(("R=%d  ships:%d  entities:%d"):format(range, #result.subLevels, #result.entities))
  local nearest = result.subLevels[1]
  if nearest then
    out.setCursorPos(1, 2)
    out.write(("ship %s  %.0fm  az %.0f  el %.0f"):format(nearest.name or nearest.id:sub(1, 8),
      nearest.distance, nearest.azimuth, nearest.elevation))
  end
end

while true do
  draw(radar.scan())
  sleep(0.5)
end
