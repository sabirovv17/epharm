import { useState } from 'react'
import type { CatalogBranch } from './placements-api'
import { Button } from '@/ui'
const width = 640,
  height = 340
const point = (lat: number, lon: number, zoom: number) => {
  const size = 256 * 2 ** zoom
  const angle = (Math.max(-85, Math.min(85, lat)) * Math.PI) / 180
  return {
    x: ((lon + 180) / 360) * size,
    y: ((1 - Math.log(Math.tan(angle) + 1 / Math.cos(angle)) / Math.PI) / 2) * size,
  }
}
const unpoint = (x: number, y: number, zoom: number) => {
  const size = 256 * 2 ** zoom
  return {
    lon: (x / size) * 360 - 180,
    lat: (Math.atan(Math.sinh(Math.PI * (1 - (2 * y) / size))) * 180) / Math.PI,
  }
}
export default function PlanningMap({
  branches,
  selected,
  onSelect,
}: {
  branches: CatalogBranch[]
  selected: string
  onSelect: (id: string) => void
}) {
  const coordinates = branches.filter(
    (branch): branch is CatalogBranch & { latitude: number; longitude: number } =>
      Number.isFinite(branch.latitude) &&
      Number.isFinite(branch.longitude) &&
      Math.abs(branch.latitude!) <= 85 &&
      Math.abs(branch.longitude!) <= 180,
  )
  const [shown, setShown] = useState(false)
  const [zoom, setZoom] = useState(5)
  const [pan, setPan] = useState<{ lat: number; lon: number } | null>(null)
  const [failed, setFailed] = useState(false)
  const [expanded, setExpanded] = useState<string[]>([])
  const active = coordinates.find((branch) => branch.id === selected)
  const center =
    pan ??
    (active
      ? { lat: active.latitude, lon: active.longitude }
      : coordinates.length
        ? {
            lat: coordinates.reduce((v, b) => v + b.latitude, 0) / coordinates.length,
            lon: coordinates.reduce((v, b) => v + b.longitude, 0) / coordinates.length,
          }
        : { lat: 0, lon: 0 })
  const origin = point(center.lat, center.lon, zoom)
  const x0 = origin.x - width / 2,
    y0 = origin.y - height / 2
  const groups = new Map<string, typeof coordinates>()
  for (const branch of coordinates) {
    const p = point(branch.latitude, branch.longitude, zoom)
    if (p.x - x0 < -20 || p.x - x0 > width + 20 || p.y - y0 < -20 || p.y - y0 > height + 20)
      continue
    const neighbors = [...groups.entries()].filter(([, members]) =>
      members.some((row) => {
        const location = point(row.latitude, row.longitude, zoom)
        return Math.hypot(location.x - p.x, location.y - p.y) < 24
      }),
    )
    for (const [key] of neighbors) groups.delete(key)
    groups.set(branch.id, [...neighbors.flatMap(([, members]) => members), branch])
  }
  const select = (id: string) => {
    setExpanded([])
    setPan(null)
    onSelect(id)
  }
  const tiles: { x: number; y: number; key: string; url: string }[] = []
  if (shown && !failed)
    for (let x = Math.floor(x0 / 256); x <= Math.floor((x0 + width) / 256); x++)
      for (let y = Math.floor(y0 / 256); y <= Math.floor((y0 + height) / 256); y++)
        if (x >= 0 && y >= 0 && x < 2 ** zoom && y < 2 ** zoom)
          tiles.push({
            x: x * 256 - x0,
            y: y * 256 - y0,
            key: `${zoom}/${x}/${y}`,
            url: `https://tile.openstreetmap.org/${zoom}/${x}/${y}.png`,
          })
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="outline" onClick={() => setShown(!shown)}>
          {shown ? 'Скрыть карту' : 'Показать карту аптек'}
        </Button>
        {shown && (
          <>
            <Button
              type="button"
              variant="outline"
              disabled={zoom >= 18}
              onClick={() => setZoom(zoom + 1)}
            >
              Приблизить
            </Button>
            <Button
              type="button"
              variant="outline"
              disabled={zoom <= 2}
              onClick={() => setZoom(zoom - 1)}
            >
              Отдалить
            </Button>
            <Button type="button" variant="outline" onClick={() => setPan(null)}>
              Центрировать
            </Button>
            {(['←', '↑', '↓', '→'] as const).map((label, i) => (
              <Button
                key={label}
                type="button"
                variant="outline"
                aria-label={`Сдвинуть карту ${label}`}
                onClick={() =>
                  setPan(
                    unpoint(
                      origin.x + (i === 0 ? -160 : i === 3 ? 160 : 0),
                      origin.y + (i === 1 ? -85 : i === 2 ? 85 : 0),
                      zoom,
                    ),
                  )
                }
              >
                {label}
              </Button>
            ))}
          </>
        )}
      </div>
      {shown && (
        <>
          <p className="text-xs text-ink-500">
            Координаты есть у {coordinates.length} из {branches.length} аптек открытой страницы. Без
            координат выбирайте аптеку в списке.
          </p>
          {expanded.length > 0 && (
            <div
              className="flex max-h-48 flex-wrap gap-2 overflow-auto"
              aria-label="Аптеки в одной точке"
            >
              {coordinates
                .filter((row) => expanded.includes(row.id))
                .map((row) => (
                  <Button
                    key={row.id}
                    type="button"
                    variant="outline"
                    onClick={() => select(row.id)}
                  >
                    {row.name} · {row.address}
                  </Button>
                ))}
            </div>
          )}
          {failed && (
            <p role="status">Подложка карты недоступна; точки и список продолжают работать.</p>
          )}
          <div
            className="relative overflow-hidden rounded-xl border border-paper-line bg-paper-input"
            style={{ aspectRatio: `${width}/${height}` }}
          >
            <svg
              viewBox={`0 0 ${width} ${height}`}
              className="h-full w-full"
              aria-label="Карта доступных аптек"
            >
              {tiles.map((tile) => (
                <foreignObject key={tile.key} x={tile.x} y={tile.y} width={256} height={256}>
                  <img
                    src={tile.url}
                    width={256}
                    height={256}
                    alt=""
                    draggable={false}
                    referrerPolicy="origin"
                    onError={() => setFailed(true)}
                  />
                </foreignObject>
              ))}
              {[...groups.entries()].map(([key, members]) => {
                const branch = members[0]
                const positions = members.map((row) => point(row.latitude, row.longitude, zoom))
                const p = {
                  x: positions.reduce((n, row) => n + row.x, 0) / positions.length,
                  y: positions.reduce((n, row) => n + row.y, 0) / positions.length,
                }
                const label =
                  members.length === 1
                    ? `Выбрать ${branch.name}, ${branch.address}`
                    : zoom < 18
                      ? `Приблизить группу из ${members.length} аптек`
                      : `Выбрать одну из ${members.length} аптек`
                const activate = () => {
                  if (members.length === 1) select(branch.id)
                  else if (zoom < 18) {
                    setPan(unpoint(p.x, p.y, zoom))
                    setZoom(Math.min(18, zoom + 2))
                    setExpanded([])
                  } else setExpanded(members.map((row) => row.id))
                }
                return (
                  <g
                    key={key}
                    transform={`translate(${p.x - x0},${p.y - y0})`}
                    role="button"
                    tabIndex={0}
                    aria-label={label}
                    onClick={activate}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault()
                        activate()
                      }
                    }}
                  >
                    <title>{label}</title>
                    <circle
                      r={members.length > 1 ? 12 : branch.id === selected ? 10 : 7}
                      className={
                        members.some((row) => row.id === selected)
                          ? 'fill-brand-green-600 stroke-white'
                          : 'fill-brand-green-500 stroke-white'
                      }
                      strokeWidth={2}
                    />
                    <text
                      y={members.length > 1 ? 4 : -13}
                      textAnchor="middle"
                      className={
                        members.length > 1 ? 'fill-white text-[11px]' : 'fill-ink-900 text-[10px]'
                      }
                    >
                      {members.length > 1 ? members.length : branch.name}
                    </text>
                  </g>
                )
              })}
            </svg>
            <a
              className="absolute bottom-0 right-0 bg-white px-2 text-xs text-ink-700"
              href="https://www.openstreetmap.org/copyright"
              target="_blank"
              rel="noreferrer"
            >
              © OpenStreetMap contributors
            </a>
          </div>
        </>
      )}
    </div>
  )
}
