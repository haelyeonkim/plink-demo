import { useRef, useState } from 'react';
import type { VenuePoint, VenueShape } from '../ticket/api';
import { AREA_COLORS, centre, PLAN_WIDTH, type Area } from './FloorPlan';

type Tool = 'select' | 'rect' | 'poly' | 'label' | 'line';
type Draft =
  | { kind: 'rect'; a: VenuePoint; b: VenuePoint }
  | { kind: 'poly' | 'line'; pts: VenuePoint[]; hover: VenuePoint };
type Drag =
  | { mode: 'move'; id: string; from: VenuePoint; original: VenueShape; before: VenueShape[]; moved: boolean }
  | { mode: 'vertex'; id: string; index: number; before: VenueShape[]; moved: boolean };

const TOOLS: Array<[Tool, string, string]> = [
  ['select', '선택 · 이동', 'M5 3l14 8-6 2-2 6z'],
  ['rect', '사각형 영역', 'M4 5h16v14H4z'],
  ['poly', '자유 영역', 'M5 7l6-3 8 5-2 10H7z'],
  ['label', '글자', 'M5 6h14M12 6v13M9 19h6'],
  ['line', '동선 화살표', 'M4 20L20 4M20 4h-7M20 4v7'],
];
const HINTS: Record<Tool, string> = {
  select: '도형을 누르면 고칠 수 있고, 끌면 옮겨져요. 고른 영역의 네모를 끌면 모양이 바뀌고, 변 가운데 점을 끌면 꼭짓점이 생겨요.',
  rect: '지도 위를 끌어서 사각형 영역을 그려요.',
  poly: '꼭짓점을 하나씩 찍어요. 첫 점을 다시 누르면 영역이 닫혀요.',
  label: '글자를 넣을 곳을 누르세요.',
  line: '시작점과 끝점을 차례로 누르면 화살표가 그려져요.',
};
const KIND: Record<VenueShape['type'], string> = { area: '영역', label: '글자', line: '동선' };
const GRID = 20;

let counter = 0;
const newId = () => `s${Date.now().toString(36)}${(counter++).toString(36)}`;

function Icon({ path }: { path: string }) {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={path} /></svg>
  );
}

/**
 * The organiser's drawing tool for one floor: areas by drag or by corners, words, and
 * arrows for the way through, over the floor's plan image when it has one. Everything is
 * kept in plan units (1000 wide), so what is drawn here lands on the same spot on a phone.
 */
export default function MapDrawer({ shapes, height, imageUrl, onChange }: {
  shapes: VenueShape[]; height: number; imageUrl: string | null; onChange: (shapes: VenueShape[]) => void;
}) {
  const svg = useRef<SVGSVGElement>(null);
  const [tool, setTool] = useState<Tool>('select');
  const [snap, setSnap] = useState(true);
  const [selected, setSelected] = useState<string | null>(null);
  const [vertex, setVertex] = useState<number | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [drag, setDrag] = useState<Drag | null>(null);
  const [history, setHistory] = useState<VenueShape[][]>([]);

  const point = (event: React.PointerEvent): VenuePoint => {
    const box = svg.current!.getBoundingClientRect();
    const fit = (v: number) => (snap ? Math.round(v / GRID) * GRID : Math.round(v));
    const x = Math.min(PLAN_WIDTH, Math.max(0, ((event.clientX - box.left) / box.width) * PLAN_WIDTH));
    const y = Math.min(height, Math.max(0, ((event.clientY - box.top) / box.height) * height));
    return [Math.min(PLAN_WIDTH, fit(x)), Math.min(height, fit(y))];
  };
  /** Every change goes through here, so it can be taken back. */
  const commit = (next: VenueShape[]) => {
    setHistory(h => [...h, shapes].slice(-50));
    onChange(next);
  };
  const add = (shape: VenueShape) => {
    commit([...shapes, shape]);
    setSelected(shape.id);
    setVertex(null);
    setTool('select');
  };
  const choose = (next: Tool) => { setTool(next); setDraft(null); };
  const finishPoly = () => {
    if (draft?.kind !== 'poly' || draft.pts.length < 3) return;
    setDraft(null);
    add({ id: newId(), type: 'area', pts: draft.pts, name: '새 영역', color: AREA_COLORS[0][0], desc: '', showName: true });
  };

  function down(event: React.PointerEvent<SVGSVGElement>) {
    if (event.button !== 0) return;
    const p = point(event);
    svg.current!.setPointerCapture(event.pointerId);
    if (tool === 'select') { setSelected(null); setVertex(null); return; }
    if (tool === 'rect') { setDraft({ kind: 'rect', a: p, b: p }); return; }
    if (tool === 'label') { add({ id: newId(), type: 'label', x: p[0], y: p[1], name: '새 글자' }); return; }
    if (!draft || draft.kind === 'rect') { setDraft({ kind: tool, pts: [p], hover: p }); return; }
    if (draft.kind === 'line') {
      setDraft(null);
      add({ id: newId(), type: 'line', pts: [draft.pts[0], p], name: '동선' });
      return;
    }
    const first = draft.pts[0];
    if (draft.pts.length >= 3 && Math.hypot(first[0] - p[0], first[1] - p[1]) < 24) { finishPoly(); return; }
    setDraft({ ...draft, pts: [...draft.pts, p] });
  }

  function move(event: React.PointerEvent<SVGSVGElement>) {
    if (!drag && !draft) return;
    const p = point(event);
    if (drag?.mode === 'vertex') {
      onChange(shapes.map(s => (s.id === drag.id && s.type !== 'label'
        ? { ...s, pts: s.pts.map((q, i) => (i === drag.index ? p : q)) } : s)));
      if (!drag.moved) setDrag({ ...drag, moved: true });
      return;
    }
    if (drag?.mode === 'move') {
      const dx = p[0] - drag.from[0];
      const dy = p[1] - drag.from[1];
      const o = drag.original;
      // A shape moves as a whole and stops at the edge of the plan.
      const xs = o.type === 'label' ? [o.x] : o.pts.map(q => q[0]);
      const ys = o.type === 'label' ? [o.y] : o.pts.map(q => q[1]);
      const mx = Math.min(PLAN_WIDTH - Math.max(...xs), Math.max(-Math.min(...xs), dx));
      const my = Math.min(height - Math.max(...ys), Math.max(-Math.min(...ys), dy));
      const moved: VenueShape = o.type === 'label' ? { ...o, x: o.x + mx, y: o.y + my }
        : { ...o, pts: o.pts.map(q => [q[0] + mx, q[1] + my] as VenuePoint) };
      onChange(shapes.map(s => (s.id === o.id ? moved : s)));
      if (!drag.moved && (mx !== 0 || my !== 0)) setDrag({ ...drag, moved: true });
      return;
    }
    if (draft?.kind === 'rect') setDraft({ ...draft, b: p });
    else if (draft) setDraft({ ...draft, hover: p });
  }

  function up() {
    if (drag) {
      // One drag is one step back, not one per pixel moved.
      if (drag.moved) setHistory(h => [...h, drag.before].slice(-50));
      setDrag(null);
      return;
    }
    if (draft?.kind === 'rect') {
      const [x0, x1] = [Math.min(draft.a[0], draft.b[0]), Math.max(draft.a[0], draft.b[0])];
      const [y0, y1] = [Math.min(draft.a[1], draft.b[1]), Math.max(draft.a[1], draft.b[1])];
      setDraft(null);
      if (x1 - x0 < GRID || y1 - y0 < GRID) return;
      add({ id: newId(), type: 'area', pts: [[x0, y0], [x1, y0], [x1, y1], [x0, y1]], name: '새 영역',
        color: AREA_COLORS[0][0], desc: '', showName: true });
    }
  }

  const grab = (shape: VenueShape) => (event: React.PointerEvent) => {
    if (tool !== 'select' || event.button !== 0) return;
    event.stopPropagation();
    svg.current!.setPointerCapture(event.pointerId);
    setSelected(shape.id);
    setVertex(null);
    setDrag({ mode: 'move', id: shape.id, from: point(event), original: shape, before: shapes, moved: false });
  };

  const current = shapes.find(s => s.id === selected) ?? null;
  const editable = tool === 'select' && current !== null && current.type !== 'label';
  const update = (patch: Partial<Area> | Partial<VenueShape>) =>
    commit(shapes.map(s => (s.id === selected ? { ...s, ...patch } as VenueShape : s)));
  const undo = () => {
    if (history.length === 0) return;
    onChange(history[history.length - 1]);
    setHistory(h => h.slice(0, -1));
    setSelected(null);
    setVertex(null);
    setDraft(null);
  };

  const cursor = tool === 'select' ? 'default' : 'crosshair';
  let draftPoints = '';
  if (draft?.kind === 'rect') {
    const { a, b } = draft;
    draftPoints = [[a[0], a[1]], [b[0], a[1]], [b[0], b[1]], [a[0], b[1]], [a[0], a[1]]].map(q => q.join(',')).join(' ');
  } else if (draft) {
    draftPoints = [...draft.pts, draft.hover].map(q => q.join(',')).join(' ');
  }

  return (
    <div className="map-drawer">
      <section className="map-drawer-main" aria-label="그리기">
        <div className="map-drawer-tools" role="toolbar" aria-label="그리기 도구">
          {TOOLS.map(([key, label, icon]) => (
            <button key={key} type="button" aria-pressed={tool === key} className={tool === key ? 'on' : ''}
              onClick={() => choose(key)}><Icon path={icon} />{label}</button>
          ))}
          <span className="map-drawer-sep" />
          <button type="button" disabled={history.length === 0} onClick={undo}>
            <Icon path="M9 14L4 9l5-5M4 9h11a5 5 0 0 1 0 10h-3" />되돌리기</button>
          <span className="map-drawer-grow" />
          <label className="console-switch"><input type="checkbox" checked={snap} onChange={() => setSnap(v => !v)} />격자에 맞추기</label>
        </div>
        <div className="map-drawer-hint">
          <span>{HINTS[tool]}</span>
          {draft?.kind === 'poly' && (<>
            <button className="btn-tiny btn-tiny-primary" type="button" disabled={draft.pts.length < 3} onClick={finishPoly}>영역 닫기</button>
            <button className="btn-tiny" type="button" onClick={() => setDraft(null)}>그리기 취소</button>
          </>)}
        </div>
        <div className="map-drawer-board">
          <svg ref={svg} viewBox={`0 0 ${PLAN_WIDTH} ${height}`} role="img" aria-label="지도 그리기 영역"
            style={{ cursor }} onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up}>
            <defs>
              <pattern id="drawer-grid" width={GRID} height={GRID} patternUnits="userSpaceOnUse">
                <path d={`M${GRID} 0H0V${GRID}`} fill="none" stroke="#e3e7ef" strokeWidth="1" />
              </pattern>
              <marker id="drawer-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                <path d="M0 0L10 5L0 10z" fill="#33405e" />
              </marker>
            </defs>
            {imageUrl && <image href={imageUrl} x="0" y="0" width={PLAN_WIDTH} height={height} preserveAspectRatio="none" />}
            <rect x="0" y="0" width={PLAN_WIDTH} height={height} fill="url(#drawer-grid)" opacity={imageUrl ? 0.5 : 1} />
            {shapes.map(s => s.type === 'area' && (
              <polygon key={s.id} points={s.pts.map(q => q.join(',')).join(' ')} fill={s.color}
                fillOpacity={s.id === selected ? 0.24 : 0.12} stroke={s.color} strokeWidth={s.id === selected ? 4 : 2}
                strokeLinejoin="round" style={{ cursor: tool === 'select' ? 'move' : cursor }} onPointerDown={grab(s)} />
            ))}
            {shapes.map(s => {
              if (s.type !== 'area' || !s.showName || !s.name) return null;
              const [x, y] = centre(s.pts);
              return <text key={`n${s.id}`} x={x} y={y} className="map-drawer-name" fill={s.color}>{s.name}</text>;
            })}
            {shapes.map(s => s.type === 'line' && (
              <line key={s.id} x1={s.pts[0][0]} y1={s.pts[0][1]} x2={s.pts[1][0]} y2={s.pts[1][1]} stroke="#33405e"
                strokeWidth={s.id === selected ? 5 : 3} strokeDasharray="10 8" markerEnd="url(#drawer-arrow)"
                style={{ cursor: tool === 'select' ? 'move' : cursor }} onPointerDown={grab(s)} />
            ))}
            {shapes.map(s => s.type === 'label' && (
              <text key={s.id} x={s.x} y={s.y} className="map-drawer-label" fill={s.id === selected ? '#315cf4' : '#122145'}
                style={{ cursor: tool === 'select' ? 'move' : cursor }} onPointerDown={grab(s)}>{s.name}</text>
            ))}
            {editable && current.type === 'area' && current.pts.map((q, i) => {
              // Halfway along each side: dragging it puts a new corner there.
              const r = current.pts[(i + 1) % current.pts.length];
              const mid: VenuePoint = [(q[0] + r[0]) / 2, (q[1] + r[1]) / 2];
              return (
                <circle key={`m${i}`} cx={mid[0]} cy={mid[1]} r="7" className="map-drawer-mid"
                  onPointerDown={event => {
                    event.stopPropagation();
                    svg.current!.setPointerCapture(event.pointerId);
                    const pts = [...current.pts.slice(0, i + 1), mid, ...current.pts.slice(i + 1)];
                    onChange(shapes.map(s => (s.id === current.id ? { ...current, pts } : s)));
                    setVertex(i + 1);
                    // The new corner and where it is dragged to are one step back.
                    setDrag({ mode: 'vertex', id: current.id, index: i + 1, before: shapes, moved: true });
                  }} />
              );
            })}
            {editable && current.pts.map((q, i) => (
              <rect key={`v${i}`} x={q[0] - 8} y={q[1] - 8} width="16" height="16" rx="3"
                className={`map-drawer-vertex${vertex === i ? ' on' : ''}`}
                onPointerDown={event => {
                  event.stopPropagation();
                  svg.current!.setPointerCapture(event.pointerId);
                  setVertex(i);
                  setDrag({ mode: 'vertex', id: current.id, index: i, before: shapes, moved: false });
                }} />
            ))}
            {draftPoints && <polyline points={draftPoints} className="map-drawer-draft" />}
            {draft && draft.kind !== 'rect' && draft.pts.map((q, i) => (
              <circle key={`d${i}`} cx={q[0]} cy={q[1]} r={i === 0 ? 9 : 5} className={i === 0 ? 'map-drawer-start' : 'map-drawer-dot'} />
            ))}
          </svg>
        </div>
      </section>

      <aside className="map-drawer-side" aria-label="속성">
        {current && (
          <section className="map-drawer-panel">
            <div className="map-drawer-panel-head">
              <b>{KIND[current.type]}</b>
              <button className="btn-tiny" type="button" onClick={() => { setSelected(null); setVertex(null); }}>선택 해제</button>
            </div>
            <label>{current.type === 'label' ? '글자' : '이름'}
              <input maxLength={30} value={current.name} onChange={e => update({ name: e.target.value })} />
            </label>
            {current.type === 'area' && (<>
              <div className="map-drawer-field">
                <span>색</span>
                <div role="group" aria-label="영역 색" className="map-drawer-swatches">
                  {AREA_COLORS.map(([color, name]) => (
                    <button key={color} type="button" aria-label={name} aria-pressed={current.color === color}
                      className={current.color === color ? 'on' : ''} style={{ background: color }}
                      onClick={() => update({ color })} />
                  ))}
                </div>
              </div>
              <label>관람객에게 보일 설명 (선택)
                <textarea rows={3} maxLength={200} value={current.desc} onChange={e => update({ desc: e.target.value })} />
              </label>
              <label className="console-switch">
                <input type="checkbox" checked={current.showName} onChange={() => update({ showName: !current.showName })} />지도에 이름 표시
              </label>
              <div className="map-drawer-row">
                <button className="btn-tiny" type="button" onClick={() => commit([...shapes.filter(s => s.id !== current.id), current])}>맨 앞으로</button>
                <button className="btn-tiny" type="button" onClick={() => commit([current, ...shapes.filter(s => s.id !== current.id)])}>맨 뒤로</button>
              </div>
              <div className="map-drawer-field">
                <span>꼭짓점 {current.pts.length}개</span>
                <button className="btn-tiny" type="button" disabled={vertex === null || current.pts.length <= 3}
                  onClick={() => {
                    if (vertex === null || current.pts.length <= 3) return;
                    update({ pts: current.pts.filter((_, i) => i !== vertex) });
                    setVertex(null);
                  }}>
                  {vertex === null ? '지울 꼭짓점을 먼저 고르세요' : current.pts.length <= 3 ? '영역은 꼭짓점이 3개는 있어야 해요' : '고른 꼭짓점 지우기'}
                </button>
              </div>
            </>)}
            <button className="btn-tiny btn-tiny-danger" type="button" onClick={() => {
              commit(shapes.filter(s => s.id !== current.id));
              setSelected(null);
              setVertex(null);
            }}>지우기</button>
          </section>
        )}
        <section className="map-drawer-panel">
          <b>이 층에 그린 것 <span className="muted">{shapes.length}</span></b>
          {shapes.length === 0 ? <p className="muted">아직 그린 것이 없어요.</p> : (
            <ul className="map-drawer-layers">
              {[...shapes].reverse().map(s => (
                <li key={s.id}>
                  <button type="button" aria-pressed={s.id === selected} className={s.id === selected ? 'on' : ''}
                    onClick={() => { setSelected(s.id); setVertex(null); setTool('select'); }}>
                    <span className="map-drawer-chip" style={s.type === 'area' ? { background: `${s.color}40`, borderColor: s.color } : undefined} />
                    <span>{s.name || '(이름 없음)'}</span>
                    <small>{KIND[s.type]}</small>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      </aside>
    </div>
  );
}
