import { useCallback, useEffect, useRef, useState } from 'react';
import { mutate } from '@shared/auth';
import InfoTip from '@shared/components/InfoTip';
import { shrinkPhoto } from '../ticket/photo';
import { openLive } from '../ticket/live';
import { useCoalesced } from '../ticket/coalesce';
import { read } from '../console/model';
import type { VenueDay, VenueFloor, VenueItem, VenuePlace, VenuePoint, VenueSection } from '../ticket/api';
import { DEFAULT_HEIGHT, FloorPlan, MAX_HEIGHT, MIN_HEIGHT, PLAN_WIDTH, useFloorHeight, useFloorSize } from './FloorPlan';
import MapDrawer from './MapDrawer';

type Kind = VenueSection['kind'];
type Row = VenueSection & { visible: boolean; updatedAt: string };
type Of<K extends Kind> = Extract<Row, { kind: K }>;

const KINDS: Array<[Kind, string, string]> = [
  ['MAP', '지도', '층별 지도 이미지를 올려요. 장소 목록의 위치가 이 위에 찍혀요.'],
  ['PLACES', '장소 목록', '화장실, 의무실, 흡연구역처럼 여러 곳을 모아요.'],
  ['TEXT', '안내문', '주차, 교통, 유의사항을 글로 적어요.'],
  ['TIMETABLE', '타임테이블', '날짜별, 무대별 일정을 올려요.'],
  ['LINK', '링크', '외부 예약 페이지 같은 곳으로 보내요.'],
];
const KIND_LABEL = Object.fromEntries(KINDS.map(([kind, label]) => [kind, label])) as Record<Kind, string>;

function summary(row: Row): string {
  switch (row.kind) {
    case 'MAP': return row.content.floors.length ? `${row.content.floors.length}개 층` : '지도 없음';
    case 'PLACES': {
      const pinned = row.content.places.filter(place => place.x !== null).length;
      return `장소 ${row.content.places.length}곳${row.content.mapId ? ` · 핀 ${pinned}곳` : ''}`;
    }
    case 'TEXT': return row.content.body.split('\n').find(line => line.trim())?.slice(0, 50) || '내용 없음';
    case 'TIMETABLE': return `${row.content.days.length}일 · 일정 ${row.content.items.length}개`;
    case 'LINK': return row.content.url || '주소 없음';
  }
}

/** The organiser's venue tab: which sections a registered ticket shows, in what order. */
export default function ConsoleVenue({ sessionId, onNotice, onError }: {
  sessionId: number; onNotice: (text: string) => void; onError: (text: string) => void;
}) {
  const base = `/api/admin/sessions/${sessionId}/venue`;
  const [rows, setRows] = useState<Row[] | null>(null);
  const [venueName, setVenueName] = useState<string | null>(null);
  const [picking, setPicking] = useState(false);
  const [editing, setEditing] = useState<number | null>(null);

  const latest = useRef(0);
  const load = useCallback(async () => {
    const seq = ++latest.current;
    try {
      const data = await read(await fetch(base)) as { venue: string | null; sections: Row[] };
      if (seq !== latest.current) return;
      setRows(data.sections);
      setVenueName(data.venue);
    } catch (err) {
      if (seq === latest.current) onError(err instanceof Error ? err.message : '행사장 정보를 불러오지 못했어요.');
    }
  }, [base, onError]);
  useEffect(() => { void load(); }, [load]);
  // Another operator's change shows here too; a section open in the editor keeps its draft.
  const nudge = useCoalesced(useCallback(() => { void load(); }, [load]), 600);
  useEffect(() => {
    const live = openLive(`/ws/admin/sessions/${sessionId}`, message => { if (message.type === 'VENUE') nudge(); });
    return () => live.close();
  }, [sessionId, nudge]);

  const send = (url: string, method: string, body?: unknown) => mutate(url, {
    method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body),
  }).then(read);
  const act = async (work: () => Promise<unknown>, done: string | null) => {
    try { const result = await work(); if (done) onNotice(done); await load(); return result; }
    catch (err) { onError(err instanceof Error ? err.message : '요청을 처리하지 못했어요.'); return null; }
  };

  const add = async (kind: Kind) => {
    const created = await act(() => send(base, 'POST', { kind, title: KIND_LABEL[kind] }), null) as Row | null;
    if (created) { setPicking(false); setEditing(created.id); }
  };
  const move = (index: number, by: number) => {
    if (!rows) return;
    const ids = rows.map(row => row.id);
    const [id] = ids.splice(index, 1);
    ids.splice(index + by, 0, id);
    void act(() => send(`${base}/order`, 'PUT', { ids }), null);
  };

  if (!rows) return <p className="console-none">행사장 정보를 불러오고 있어요.</p>;
  const open = rows.find(row => row.id === editing) ?? null;
  if (open) {
    return (
      <VenueEditor key={open.id} base={base} row={open} maps={rows.filter((r): r is Of<'MAP'> => r.kind === 'MAP')}
        onError={onError}
        onSave={(title, content) => act(() => send(`${base}/${open.id}`, 'PUT', { title, content }), `'${title}' 섹션을 저장했어요.`)
          .then(ok => { if (ok) setEditing(null); return !!ok; })}
        onClose={() => setEditing(null)} />
    );
  }

  const shown = rows.filter(row => row.visible).length;
  return (
    <div className="console-stack">
      <div className="console-card-head">
        <p className="console-summary">공개 {shown} · 전체 {rows.length}
          {venueName ? ` · 장소 ${venueName}` : ''}</p>
        <button className="btn-primary" type="button" aria-expanded={picking} onClick={() => setPicking(p => !p)}>섹션 추가</button>
      </div>

      {picking && (
        <section className="console-card" aria-label="추가할 섹션 종류">
          <h3>어떤 섹션을 추가할까요?</h3>
          <div className="venue-kinds">
            {KINDS.map(([kind, label, hint]) => (
              <button key={kind} type="button" onClick={() => void add(kind)}>
                <b>{label}</b><small>{hint}</small>
              </button>
            ))}
          </div>
        </section>
      )}

      <div className="console-table-box">
        <table className="console-table">
          <thead><tr><th>순서</th><th>섹션</th><th>종류</th><th>공개</th><th aria-label="작업" /></tr></thead>
          <tbody>
            {rows.map((row, index) => (
              <tr key={row.id} className={row.visible ? '' : 'is-off'}>
                <td className="row-actions">
                  <button className="btn-tiny" type="button" aria-label={`${row.title} 위로`} disabled={index === 0}
                    onClick={() => move(index, -1)}>↑</button>
                  <button className="btn-tiny" type="button" aria-label={`${row.title} 아래로`} disabled={index === rows.length - 1}
                    onClick={() => move(index, 1)}>↓</button>
                </td>
                <td className="wrap"><b>{row.title}</b><small className="cell-note">{summary(row)}</small>
                  {readDraft(draftKey(base, row.id)) && <small className="cell-note venue-has-draft">이 브라우저에 저장하지 않은 작업이 있어요</small>}</td>
                <td className="muted">{KIND_LABEL[row.kind]}</td>
                <td>
                  <label className="console-switch venue-visible">
                    <input type="checkbox" checked={row.visible} onChange={() => void act(
                      () => send(`${base}/${row.id}/visible`, 'POST', { visible: !row.visible }),
                      row.visible ? `'${row.title}'을(를) 숨겼어요.` : `'${row.title}'을(를) 공개했어요.`)} />
                    {row.visible ? '공개' : '숨김'}
                  </label>
                </td>
                <td className="row-actions">
                  <button className="btn-tiny" type="button" onClick={() => setEditing(row.id)}>편집</button>
                  <button className="btn-tiny btn-tiny-danger" type="button" onClick={() => {
                    if (window.confirm(`'${row.title}' 섹션을 지울까요? 되돌릴 수 없어요.`)) {
                      dropDraft(base, row.id);
                      void act(() => send(`${base}/${row.id}`, 'DELETE'), '섹션을 지웠어요.');
                    }
                  }}>삭제</button>
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={5} className="console-none">아직 섹션이 없어요. 섹션 추가로 지도, 화장실 위치, 주차 안내, 타임테이블을 올려 보세요.</td></tr>
            )}
          </tbody>
        </table>
      </div>
      <p className="muted venue-note">입장권을 등록한 관람객의 행사장 탭에 이 순서대로 보여요. 새 섹션은 숨김으로 만들어지니 내용을 채운 뒤 공개해 주세요.
        공개된 섹션이 없으면 행사장 탭이 나타나지 않아요.</p>
    </div>
  );
}

// ---- One section open ----

// ---- Work in progress, kept in this browser ----

/** What is being edited, saved as it changes: a reload or a closed tab does not lose it. */
interface Draft { title: string; content: Row['content']; base: string; at: string }

const draftKey = (base: string, id: number) => `plink.venueDraft:${base}/${id}`;

function readDraft(key: string): Draft | null {
  try {
    const raw = window.localStorage.getItem(key);
    return raw ? JSON.parse(raw) as Draft : null;
  } catch { return null; }
}

function writeDraft(key: string, draft: Draft | null) {
  try {
    if (draft) window.localStorage.setItem(key, JSON.stringify(draft));
    else window.localStorage.removeItem(key);
  } catch { /* storage full or switched off: the editor still works, just without the copy */ }
}

export function dropDraft(base: string, id: number) { writeDraft(draftKey(base, id), null); }

function clockOf(iso: string): string {
  const d = new Date(iso);
  return `${d.getMonth() + 1}.${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function VenueEditor({ base, row, maps, onSave, onClose, onError }: {
  base: string; row: Row; maps: Of<'MAP'>[];
  onSave: (title: string, content: unknown) => Promise<boolean>; onClose: () => void; onError: (text: string) => void;
}) {
  const key = draftKey(base, row.id);
  const [title, setTitle] = useState(row.title);
  const [content, setContent] = useState<Row['content']>(row.content);
  const set = <T,>(patch: Partial<T>) => setContent(current => ({ ...current, ...patch }));
  // A copy left from before - a reload, a closed tab - is offered rather than applied:
  // the saved section may be the one wanted.
  const [offer, setOffer] = useState<Draft | null>(() => {
    const draft = readDraft(key);
    if (!draft) return null;
    const same = draft.title === row.title && JSON.stringify(draft.content) === JSON.stringify(row.content);
    if (same) { writeDraft(key, null); return null; }
    return draft;
  });
  const dirty = title !== row.title || JSON.stringify(content) !== JSON.stringify(row.content);

  // Kept as it changes, a moment after the last change. Not while a copy is on offer: one
  // keystroke would otherwise write over the work being offered back.
  useEffect(() => {
    if (offer) return;
    const timer = window.setTimeout(() => writeDraft(key, dirty
      ? { title, content, base: row.updatedAt, at: new Date().toISOString() } : null), 600);
    return () => window.clearTimeout(timer);
  }, [key, title, content, dirty, offer, row.updatedAt]);
  // Leaving the page with changes not saved to the server asks first.
  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  const save = () => {
    void onSave(title, content).then(ok => { if (ok) writeDraft(key, null); });
  };

  return (
    <form className="console-card venue-editor" onSubmit={e => { e.preventDefault(); save(); }}>
      <div className="console-card-head">
        <h3>{KIND_LABEL[row.kind]} 편집</h3>
        <span className="venue-editor-state">
          {dirty && !offer && <small className="muted">이 브라우저에 임시 저장 중 · 서버에는 저장 전</small>}
          <span className={`pill pill-${row.visible ? 'ok' : 'off'}`}>{row.visible ? '공개 중' : '숨김'}</span>
        </span>
      </div>
      {offer && (
        <div className="venue-draft" role="status">
          <p><b>저장하지 않은 작업이 있어요.</b> {clockOf(offer.at)}에 이 브라우저에서 고치던 내용이에요.
            {offer.base !== row.updatedAt && ' 그 뒤로 이 섹션이 다른 곳에서 저장됐어요. 이어서 하면 그 내용을 덮어쓰게 돼요.'}</p>
          <span className="row-actions">
            <button className="btn-tiny btn-tiny-primary" type="button" onClick={() => {
              setTitle(offer.title);
              setContent(offer.content);
              setOffer(null);
            }}>이어서 하기</button>
            <button className="btn-tiny" type="button" onClick={() => { writeDraft(key, null); setOffer(null); }}>버리기</button>
          </span>
        </div>
      )}
      <div className="console-fields">
        <label className="span">섹션 이름<input required maxLength={40} value={title} onChange={e => setTitle(e.target.value)} /></label>
      </div>
      {row.kind === 'TEXT' && (
        <div className="console-fields">
          <label className="span">내용<textarea rows={12} maxLength={5000} value={(content as Of<'TEXT'>['content']).body}
            onChange={e => set<Of<'TEXT'>['content']>({ body: e.target.value })} /></label>
        </div>
      )}
      {row.kind === 'LINK' && (
        <div className="console-fields">
          <label className="span">주소<input type="url" maxLength={500} placeholder="https://"
            value={(content as Of<'LINK'>['content']).url} onChange={e => set<Of<'LINK'>['content']>({ url: e.target.value })} /></label>
          <label className="span">설명 (선택)<input maxLength={120} placeholder="예: 행사 당일 물품보관소 예약"
            value={(content as Of<'LINK'>['content']).description}
            onChange={e => set<Of<'LINK'>['content']>({ description: e.target.value })} /></label>
        </div>
      )}
      {row.kind === 'MAP' && (
        <MapEditor base={base} floors={(content as Of<'MAP'>['content']).floors} onError={onError}
          onChange={edit => setContent(current => ({ ...current, floors: edit((current as Of<'MAP'>['content']).floors) }))} />
      )}
      {row.kind === 'PLACES' && (
        <PlacesEditor base={base} maps={maps} value={content as Of<'PLACES'>['content']}
          onChange={next => setContent(next)} />
      )}
      {row.kind === 'TIMETABLE' && (
        <TimetableEditor value={content as Of<'TIMETABLE'>['content']} onChange={next => setContent(next)} />
      )}
      <div className="button-row">
        <button className="btn-primary" type="submit">저장</button>
        <button className="btn-secondary" type="button" onClick={onClose}>목록으로</button>
      </div>
    </form>
  );
}

/** The plan shapes a floor without an image can take, as heights on a plan 1000 wide. */
const CANVAS_SHAPES: Array<[number, string]> = [[700, '가로형'], [1000, '정사각형'], [1400, '세로형']];

let floorCounter = 0;
const newFloorId = () => `f${Date.now().toString(36)}${(floorCounter++).toString(36)}`;

/** A floor at a new height, keeping what was drawn on it: anything below the edge moves up to it. */
function withHeight(floor: VenueFloor, next: number): VenueFloor {
  return {
    ...floor, height: next,
    shapes: (floor.shapes ?? []).map(shape => (shape.type === 'label'
      ? { ...shape, y: Math.min(shape.y, next) }
      : { ...shape, pts: shape.pts.map(([x, y]) => [x, Math.min(y, next)] as VenuePoint) })),
  };
}

/**
 * The floors of a map. Every change is given as a function of the floors as they are when
 * it lands, and finds its floor by id: an upload that finishes while the organiser is
 * still drawing must not put back the floors as they were when it started.
 */
function MapEditor({ base, floors, onChange, onError }: {
  base: string; floors: VenueFloor[]; onChange: (change: (floors: VenueFloor[]) => VenueFloor[]) => void;
  onError: (text: string) => void;
}) {
  const [activeId, setActiveId] = useState<string | null>(floors[0]?.id ?? null);
  const [uploading, setUploading] = useState(false);
  const floor = floors.find(f => f.id === activeId) ?? floors[0];
  const index = floor ? floors.indexOf(floor) : -1;
  const imageUrl = floor?.imageId ? `${base}/images/${floor.imageId}` : null;
  const { height, settled } = useFloorSize(floor, imageUrl);
  const change = (id: string, edit: (f: VenueFloor) => VenueFloor) =>
    onChange(current => current.map(f => (f.id === id ? edit(f) : f)));
  const update = (patch: Partial<VenueFloor>) => { if (floor) change(floor.id, f => ({ ...f, ...patch })); };
  // A floor that only knew its height from its image says it outright once edited, so the
  // phone draws it the same way without loading the image first.
  useEffect(() => {
    if (floor && floor.height == null && settled) update({ height });
  }, [floor, settled, height]); // eslint-disable-line react-hooks/exhaustive-deps

  async function pick(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file || !floor) return;
    // The floor the image was picked for, wherever it is by the time the upload is done.
    const target = floor.id;
    setUploading(true);
    try {
      const small = await shrinkPhoto(file, 2400);
      const size = await new Promise<number>(resolve => {
        const image = new Image();
        image.onload = () => resolve(Math.round((PLAN_WIDTH * image.naturalHeight) / Math.max(1, image.naturalWidth)));
        image.onerror = () => resolve(DEFAULT_HEIGHT);
        image.src = URL.createObjectURL(small);
      });
      const form = new FormData();
      form.append('file', small, 'plan.jpg');
      const { imageId } = await read(await mutate(`${base}/images`, { method: 'POST', body: form })) as { imageId: string };
      // The plan takes the image's proportions, so pins put on it stay where they were put.
      change(target, f => withHeight({ ...f, imageId }, Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, size))));
    } catch (err) { onError(err instanceof Error ? err.message : '지도 이미지를 올리지 못했어요.'); }
    finally { setUploading(false); }
  }

  return (
    <div className="venue-floors">
      <div className="chip-row" role="group" aria-label="층">
        {floors.map((f, i) => (
          <button key={f.id} type="button" className={i === index ? 'on' : ''} aria-pressed={i === index}
            onClick={() => setActiveId(f.id)}>{f.name || `${i + 1}층`}</button>
        ))}
        {floors.length < 10 && (
          <button type="button" onClick={() => {
            const id = newFloorId();
            onChange(current => [...current, { id, name: `${current.length + 1}층`, imageId: null, height: DEFAULT_HEIGHT, shapes: [] }]);
            setActiveId(id);
          }}>+ 층 추가</button>
        )}
      </div>
      {floor ? (<>
        <div className="venue-floor-fields">
          <label>층 이름<input required maxLength={20} value={floor.name} placeholder="예: 1층"
            onChange={e => update({ name: e.target.value })} /></label>
          {!floor.imageId && (
            <label>캔버스 모양
              <select value={CANVAS_SHAPES.some(([h]) => h === height) ? height : ''}
                onChange={e => { const next = Number(e.target.value); change(floor.id, f => withHeight(f, next)); }}>
                {!CANVAS_SHAPES.some(([h]) => h === height) && <option value="">지금 크기</option>}
                {CANVAS_SHAPES.map(([h, label]) => <option key={h} value={h}>{label}</option>)}
              </select></label>
          )}
          <span className="row-actions">
            <label className="btn-tiny">
              {uploading ? '올리는 중…' : floor.imageId ? '도면 이미지 바꾸기' : '도면 이미지 깔기 (선택)'}
              <input type="file" accept="image/jpeg,image/png,image/webp,image/heic,image/heif" hidden
                disabled={uploading} onChange={e => void pick(e)} />
            </label>
            {floor.imageId && <button className="btn-tiny" type="button" onClick={() => update({ imageId: null })}>도면 이미지 빼기</button>}
            <button className="btn-tiny btn-tiny-danger" type="button" onClick={() => {
              if (window.confirm(`'${floor.name}' 층을 뺄까요? 그린 것도 함께 사라지고, 이 층에 찍은 장소 핀은 저장할 때 빠져요.`)) {
                const gone = floor.id;
                onChange(current => current.filter(f => f.id !== gone));
                setActiveId(floors[index - 1]?.id ?? floors[index + 1]?.id ?? null);
              }
            }}>층 빼기</button>
          </span>
        </div>
        <MapDrawer key={floor.id} shapes={floor.shapes ?? []} height={height} imageUrl={imageUrl}
          onChange={shapes => update({ shapes })} />
      </>) : <p className="console-none">층을 추가해 주세요.</p>}
      <p className="muted venue-note">도면 이미지가 있으면 그 위에 따라 그리고, 없으면 빈 캔버스에 바로 그려요. 화장실 같은 장소의 핀은 장소 목록 섹션에서 찍어요.</p>
    </div>
  );
}

function PlacesEditor({ base, maps, value, onChange }: {
  base: string; maps: Of<'MAP'>[]; value: Of<'PLACES'>['content']; onChange: (next: Of<'PLACES'>['content']) => void;
}) {
  const map = maps.find(m => m.id === value.mapId) ?? null;
  const floors = map?.content.floors ?? [];
  const [floor, setFloor] = useState(0);
  const [pinning, setPinning] = useState<number | null>(null);
  const [tagText, setTagText] = useState(value.tags.join(', '));
  const update = (index: number, patch: Partial<VenuePlace>) =>
    onChange({ ...value, places: value.places.map((place, i) => (i === index ? { ...place, ...patch } : place)) });
  const plan = floors[floor];
  const planUrl = plan?.imageId ? `${base}/images/${plan.imageId}` : null;
  const planHeight = useFloorHeight(plan, planUrl);

  function placePin(event: React.MouseEvent<HTMLButtonElement>) {
    if (pinning === null) return;
    const box = event.currentTarget.getBoundingClientRect();
    const x = Math.round(((event.clientX - box.left) / box.width) * 1000) / 10;
    const y = Math.round(((event.clientY - box.top) / box.height) * 1000) / 10;
    if (!plan) return;
    update(pinning, { floorId: plan.id, x, y });
    setPinning(null);
  }

  return (
    <div className="venue-places-editor">
      <div className="console-fields">
        <label>위치를 찍을 지도
          <select value={value.mapId ?? ''} onChange={e => {
            const mapId = e.target.value ? Number(e.target.value) : null;
            // Points belong to one map's plans; another map's floors would misplace them.
            onChange({ ...value, mapId, places: value.places.map(place => ({ ...place, floorId: null, x: null, y: null })) });
            setFloor(0);
          }}>
            <option value="">지도 없이 목록만</option>
            {maps.map(m => <option key={m.id} value={m.id}>{m.title}</option>)}
          </select></label>
        <label>관람객이 거를 수 있는 표시
          <input value={tagText} placeholder="예: 장애인용, 기저귀 교환대" onChange={e => setTagText(e.target.value)}
            onBlur={() => {
              const tags = [...new Set(tagText.split(',').map(t => t.trim()).filter(Boolean))].slice(0, 10);
              setTagText(tags.join(', '));
              onChange({ ...value, tags, places: value.places.map(place => ({ ...place, tags: place.tags.filter(t => tags.includes(t)) })) });
            }} /></label>
      </div>

      <div className="venue-places-grid">
        {map && (
          <section className="venue-pin-board" aria-label="지도">
            {floors.length > 1 && (
              <div className="chip-row" role="group" aria-label="층 선택">
                {floors.map((f, i) => (
                  <button key={i} type="button" className={floor === i ? 'on' : ''} aria-pressed={floor === i}
                    onClick={() => setFloor(i)}>{f.name}</button>
                ))}
              </div>
            )}
            {plan && (planUrl || (plan.shapes ?? []).length > 0) ? (
              <button type="button" className={`venue-pin-plan${pinning !== null ? ' pinning' : ''}`}
                aria-label="지도에서 위치 지정" disabled={pinning === null} onClick={placePin}>
                <FloorPlan floor={plan} imageUrl={planUrl} height={planHeight}>
                  {value.places.map((place, i) => place.floorId === plan.id && place.x !== null && place.y !== null && (
                    <span key={i} className={`venue-pin-dot${pinning === i ? ' on' : ''}`}
                      style={{ left: `${place.x}%`, top: `${place.y}%` }}>{i + 1}</span>
                  ))}
                </FloorPlan>
              </button>
            ) : <p className="console-none">이 층은 아직 비어 있어요. 지도 섹션에서 도면을 올리거나 그려 주세요.</p>}
            <p className={pinning !== null ? 'venue-pin-hint on' : 'muted venue-pin-hint'}>
              {pinning !== null ? `${value.places[pinning]?.name || '새 장소'}의 위치를 지도에서 눌러 주세요.`
                : '장소마다 핀 찍기를 누른 뒤 지도에서 위치를 눌러요.'}</p>
          </section>
        )}

        <section className="venue-place-rows" aria-label="장소">
          {value.places.map((place, i) => (
            <div key={i} className={`venue-place-row${pinning === i ? ' pinning' : ''}${map && place.x === null ? ' unpinned' : ''}`}>
              <div className="venue-place-line">
                <span className="venue-pin-num">{i + 1}</span>
                <input aria-label="장소 이름" required maxLength={40} value={place.name} placeholder="장소 이름"
                  onChange={e => update(i, { name: e.target.value })} />
                <button className="btn-tiny btn-tiny-danger" type="button" aria-label={`${place.name || '장소'} 삭제`}
                  onClick={() => { setPinning(null); onChange({ ...value, places: value.places.filter((_, j) => j !== i) }); }}>삭제</button>
              </div>
              <input aria-label="위치 설명" maxLength={80} value={place.where} placeholder="찾아가는 설명 (예: 안내데스크 뒤)"
                onChange={e => update(i, { where: e.target.value })} />
              <div className="venue-place-line">
                {value.tags.map(tag => (
                  <label key={tag} className="console-switch">
                    <input type="checkbox" checked={place.tags.includes(tag)} onChange={() => update(i, {
                      tags: place.tags.includes(tag) ? place.tags.filter(t => t !== tag) : [...place.tags, tag] })} />{tag}
                  </label>
                ))}
                {map && (
                  <button className="btn-tiny" type="button" aria-pressed={pinning === i}
                    onClick={() => {
                      setPinning(pinning === i ? null : i);
                      const at = floors.findIndex(f => f.id === place.floorId);
                      if (at >= 0) setFloor(at);
                    }}>
                    {pinning === i ? '찍는 중…' : place.x === null ? '핀 찍기'
                      : `핀 옮기기 (${floors.find(f => f.id === place.floorId)?.name ?? ''})`}</button>
                )}
              </div>
            </div>
          ))}
          {value.places.length < 100 && (
            <button className="btn-secondary" type="button" onClick={() => {
              onChange({ ...value, places: [...value.places, { name: '', where: '', floorId: null, x: null, y: null, tags: [] }] });
              if (map) setPinning(value.places.length);
            }}>장소 추가</button>
          )}
        </section>
      </div>
    </div>
  );
}

function TimetableEditor({ value, onChange }: {
  value: Of<'TIMETABLE'>['content']; onChange: (next: Of<'TIMETABLE'>['content']) => void;
}) {
  const [stageText, setStageText] = useState(value.stages.join(', '));
  const days = value.days;
  const setDay = (index: number, patch: Partial<VenueDay>) =>
    onChange({ ...value, days: days.map((d, i) => (i === index ? { ...d, ...patch } : d)) });
  const setItem = (index: number, patch: Partial<VenueItem>) =>
    onChange({ ...value, items: value.items.map((item, i) => (i === index ? { ...item, ...patch } : item)) });
  const removeDay = (index: number) => onChange({
    ...value,
    days: days.filter((_, i) => i !== index),
    // Items keep pointing at the same day after the ones before it move up.
    items: value.items.filter(item => item.day !== index).map(item => (item.day > index ? { ...item, day: item.day - 1 } : item)),
  });
  return (
    <div className="venue-timetable-editor">
      <h4>날짜 <InfoTip label="날짜">날짜를 적어 두면 그날 관람객 화면에 진행 중인 일정이 표시돼요.</InfoTip></h4>
      {days.map((day, i) => (
        <div key={i} className="venue-day-row">
          <input aria-label="날짜 이름" required maxLength={20} value={day.label} placeholder="예: DAY 1"
            onChange={e => setDay(i, { label: e.target.value })} />
          <input aria-label="날짜" type="date" value={day.date ?? ''} onChange={e => setDay(i, { date: e.target.value || null })} />
          <button className="btn-tiny btn-tiny-danger" type="button" onClick={() => removeDay(i)}>빼기</button>
        </div>
      ))}
      {days.length < 10 && (
        <button className="btn-tiny" type="button" onClick={() => onChange({ ...value, days: [...days, { label: `DAY ${days.length + 1}`, date: null }] })}>날짜 추가</button>
      )}

      <div className="console-fields">
        <label className="span">무대 (쉼표로 나눠 적어요)
          <input value={stageText} placeholder="예: 메인 스테이지, B홀, 야외무대" onChange={e => setStageText(e.target.value)}
            onBlur={() => {
              const stages = [...new Set(stageText.split(',').map(s => s.trim()).filter(Boolean))].slice(0, 12);
              setStageText(stages.join(', '));
              // A stage taken away leaves its items without one rather than on another stage.
              onChange({ ...value, stages, items: value.items.map(item => {
                const name = item.stage === null ? null : value.stages[item.stage];
                const at = name === null ? -1 : stages.indexOf(name);
                return { ...item, stage: at >= 0 ? at : null };
              }) });
            }} /></label>
      </div>

      <h4>일정</h4>
      {days.length === 0 ? <p className="console-none">날짜를 먼저 추가해 주세요.</p> : (
        <div className="console-table-box">
          <table className="console-table venue-items-table">
            <thead><tr><th>날짜</th><th>시작</th><th>끝</th><th>무대</th><th>일정</th><th>메모</th><th aria-label="작업" /></tr></thead>
            <tbody>
              {value.items.map((item, i) => (
                <tr key={i}>
                  <td><select aria-label="날짜" value={item.day} onChange={e => setItem(i, { day: Number(e.target.value) })}>
                    {days.map((d, j) => <option key={j} value={j}>{d.label}</option>)}</select></td>
                  <td><input aria-label="시작" type="time" required value={item.start} onChange={e => setItem(i, { start: e.target.value })} /></td>
                  <td><input aria-label="끝" type="time" value={item.end ?? ''} onChange={e => setItem(i, { end: e.target.value || null })} /></td>
                  <td><select aria-label="무대" value={item.stage ?? ''} onChange={e => setItem(i, { stage: e.target.value === '' ? null : Number(e.target.value) })}>
                    <option value="">-</option>
                    {value.stages.map((s, j) => <option key={j} value={j}>{s}</option>)}</select></td>
                  <td><input aria-label="일정 이름" required maxLength={60} value={item.title} onChange={e => setItem(i, { title: e.target.value })} /></td>
                  <td><input aria-label="메모" maxLength={120} value={item.note} onChange={e => setItem(i, { note: e.target.value })} /></td>
                  <td><button className="btn-tiny btn-tiny-danger" type="button"
                    onClick={() => onChange({ ...value, items: value.items.filter((_, j) => j !== i) })}>삭제</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {days.length > 0 && value.items.length < 300 && (
        <button className="btn-secondary" type="button" onClick={() => {
          const last = value.items[value.items.length - 1];
          onChange({ ...value, items: [...value.items, { day: last?.day ?? 0, start: last?.end ?? last?.start ?? '10:00', end: null,
            stage: last?.stage ?? null, title: '', note: '' }] });
        }}>일정 추가</button>
      )}
      <p className="muted venue-note">저장하면 날짜와 시작 시각 순서로 정렬돼요.</p>
    </div>
  );
}
