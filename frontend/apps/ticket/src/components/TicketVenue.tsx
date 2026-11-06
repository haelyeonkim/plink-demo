import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { venueImageUrl, type VenueSection, type VenueView } from '../ticket/api';

type Section<K extends VenueSection['kind']> = Extract<VenueSection, { kind: K }>;

/** Stroke drawings for each kind of section, as on the tab bar. */
const KIND_ICON: Record<VenueSection['kind'], string> = {
  MAP: 'M9 4 3 6v14l6-2 6 2 6-2V4l-6 2-6-2zM9 4v14M15 6v14',
  PLACES: 'M12 21s-7-6.1-7-11a7 7 0 0 1 14 0c0 4.9-7 11-7 11zM12 7.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z',
  TEXT: 'M6 3h9l4 4v14H6zM9 12h7M9 16h7M9 8h3',
  TIMETABLE: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3 2',
  LINK: 'M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5',
};

/** Pin colours, one per list of places on a map, told apart by lightness as well as hue. */
const PIN_COLORS = ['#315cf4', '#14855f', '#ad6500', '#bf3226', '#6b3fc4', '#0b6f80'];

function Icon({ path, size = 20 }: { path: string; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={path} /></svg>
  );
}

// ---- Time, read on the holder's phone ----

function today(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function nowHm(): string {
  const d = new Date();
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

type ItemState = 'done' | 'now' | 'next';

/**
 * Where an item stands for someone reading it now. Only items on today's date move: a
 * programme for another day is all still to come. An item with no end runs until the
 * next one on its stage starts.
 */
function stateOf(table: Section<'TIMETABLE'>['content'], index: number, date: string, hm: string): ItemState {
  const item = table.items[index];
  const day = table.days[item.day];
  if (!day?.date || day.date !== date) return day?.date && day.date < date ? 'done' : 'next';
  if (item.start > hm) return 'next';
  if (item.end) return item.end <= hm ? 'done' : 'now';
  const overtaken = table.items.some((other, i) => i !== index && other.day === item.day
    && other.stage === item.stage && other.start > item.start && other.start <= hm);
  return overtaken ? 'done' : 'now';
}

/** The day a timetable opens on: today when it has one, otherwise its first. */
function openingDay(table: Section<'TIMETABLE'>['content']): number {
  const at = table.days.findIndex(day => day.date === today());
  return at >= 0 ? at : 0;
}

function summary(section: VenueSection): string {
  switch (section.kind) {
    case 'MAP': return section.content.floors.map(floor => floor.name).join(' · ') || '지도';
    case 'PLACES': return `${section.content.places.length}곳`;
    case 'TEXT': return section.content.body.split('\n').find(line => line.trim())?.slice(0, 40) ?? '';
    case 'TIMETABLE': {
      const at = section.content.days.findIndex(day => day.date === today());
      if (at >= 0) return `오늘 일정 ${section.content.items.filter(item => item.day === at).length}개`;
      return `${section.content.days.length}일 · 일정 ${section.content.items.length}개`;
    }
    case 'LINK': return section.content.description || '외부 사이트로 열려요';
  }
}

/** The venue tab: the organiser's sections, and each one opened. */
export default function TicketVenue({ sessionId, token, eventName, view }: {
  sessionId: string; token: string; eventName: string; view: VenueView;
}) {
  const [params, setParams] = useSearchParams();
  const openId = Number(params.get('section')) || null;
  const open = view.sections.find(section => section.id === openId) ?? null;
  const show = (section: number | null, pin?: string) => {
    setParams(section === null ? { view: 'venue' } : pin ? { view: 'venue', section: String(section), pin }
      : { view: 'venue', section: String(section) });
    window.scrollTo(0, 0);
  };

  if (open) {
    return (
      <div className="venue">
        <header className="venue-head">
          <button type="button" className="venue-back" aria-label="행사장 안내로 돌아가기" onClick={() => show(null)}>
            <Icon path="M15 6l-6 6 6 6" size={22} />
          </button>
          <h2>{open.title}</h2>
        </header>
        {open.kind === 'MAP' && (
          // A place opened from its list starts the map on that place.
          <VenueMap key={params.get('pin') ?? ''} sessionId={sessionId} token={token} map={open}
            sections={view.sections} pin={params.get('pin')} />
        )}
        {open.kind === 'PLACES' && (
          <VenuePlaces section={open} map={view.sections.find(s => s.id === open.content.mapId && s.kind === 'MAP') as Section<'MAP'> | undefined}
            onMap={(mapId, index) => show(mapId, `${open.id}-${index}`)} />
        )}
        {open.kind === 'TEXT' && <p className="venue-text">{open.content.body}</p>}
        {open.kind === 'TIMETABLE' && <VenueTimetable table={open.content} />}
      </div>
    );
  }

  const table = view.sections.find(section => section.kind === 'TIMETABLE') as Section<'TIMETABLE'> | undefined;
  return (
    <div className="venue">
      <header className="venue-title">
        <span>{eventName}</span>
        <h2>행사장 안내</h2>
      </header>
      {view.venue && <VenueAddress venue={view.venue} />}
      {table && <NowCard section={table} onOpen={() => show(table.id)} />}
      {view.sections.length > 0 && (
        <nav className="venue-list" aria-label="행사장 안내 목록">
          {view.sections.map(section => section.kind === 'LINK' ? (
            section.content.url ? (
              <a key={section.id} href={section.content.url} target="_blank" rel="noopener noreferrer">
                <span className="venue-list-icon"><Icon path={KIND_ICON.LINK} /></span>
                <span className="venue-list-main"><b>{section.title}</b><small>{summary(section)}</small></span>
              </a>
            ) : null
          ) : (
            <button key={section.id} type="button" onClick={() => show(section.id)}>
              <span className="venue-list-icon"><Icon path={KIND_ICON[section.kind]} /></span>
              <span className="venue-list-main"><b>{section.title}</b><small>{summary(section)}</small></span>
              <Icon path="M9 6l6 6-6 6" size={18} />
            </button>
          ))}
        </nav>
      )}
    </div>
  );
}

function VenueAddress({ venue }: { venue: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try { await navigator.clipboard.writeText(venue); setCopied(true); window.setTimeout(() => setCopied(false), 2000); }
    catch { /* nothing to say: the address is on screen to read */ }
  };
  return (
    <section className="venue-address">
      <b>{venue}</b>
      <div className="button-row">
        <a className="btn-primary" href={`https://map.naver.com/p/search/${encodeURIComponent(venue)}`}
          target="_blank" rel="noopener noreferrer">길찾기</a>
        <button className="btn-secondary" type="button" onClick={() => void copy()}>{copied ? '복사했어요' : '장소 복사'}</button>
      </div>
    </section>
  );
}

/** What is on now and next, today: the reason most people open the timetable. */
function NowCard({ section, onOpen }: { section: Section<'TIMETABLE'>; onOpen: () => void }) {
  const table = section.content;
  const date = today();
  const hm = nowHm();
  const at = table.days.findIndex(day => day.date === date);
  if (at < 0) return null;
  const todays = table.items.map((item, i) => ({ item, state: stateOf(table, i, date, hm) })).filter(({ item }) => item.day === at);
  const now = todays.filter(entry => entry.state === 'now');
  const next = todays.find(entry => entry.state === 'next');
  if (now.length === 0 && !next) return null;
  const stage = (index: number | null) => (index === null ? null : table.stages[index] ?? null);
  return (
    <button type="button" className="venue-now" onClick={onOpen}>
      <span className="venue-now-head">
        {now.length > 0 ? <span className="venue-live">지금 진행 중</span> : <span className="venue-soon">다음 일정</span>}
        <span className="venue-now-all">전체 일정</span>
      </span>
      {(now.length > 0 ? now.slice(0, 2) : [next!]).map(({ item }, i) => (
        <span key={i} className="venue-now-item">
          <b>{item.title}</b>
          <small>{[stage(item.stage), item.end ? `${item.start} – ${item.end}` : item.start].filter(Boolean).join(' · ')}</small>
        </span>
      ))}
      {now.length > 0 && next && (
        <span className="venue-now-next">다음 <b>{next.item.start}</b> · {[stage(next.item.stage), next.item.title].filter(Boolean).join(' · ')}</span>
      )}
    </button>
  );
}

// ---- Map ----

interface Pin { key: string; list: number; color: string; mark: string; listTitle: string; name: string; where: string; floor: number; x: number; y: number }

function VenueMap({ sessionId, token, map, sections, pin }: {
  sessionId: string; token: string; map: Section<'MAP'>; sections: VenueSection[]; pin: string | null;
}) {
  const floors = map.content.floors;
  // Every list of places put on this map, each in its own colour.
  const lists = sections.filter((s): s is Section<'PLACES'> => s.kind === 'PLACES' && s.content.mapId === map.id);
  const pins: Pin[] = lists.flatMap((list, l) => list.content.places.flatMap((place, i) =>
    place.floor !== null && place.x !== null && place.y !== null ? [{
      key: `${list.id}-${i}`, list: list.id, color: PIN_COLORS[l % PIN_COLORS.length],
      mark: list.title.slice(0, 1), listTitle: list.title, name: place.name, where: place.where,
      floor: place.floor, x: place.x, y: place.y,
    }] : []));
  const asked = pins.find(p => p.key === pin) ?? null;
  const [floor, setFloor] = useState(asked?.floor ?? 0);
  const [filter, setFilter] = useState<number | null>(asked?.list ?? null);
  const [picked, setPicked] = useState<string | null>(asked?.key ?? null);
  const [zoomed, setZoomed] = useState(false);
  const plan = floors[floor];
  const shown = pins.filter(p => p.floor === floor && (filter === null || p.list === filter));
  const current = shown.find(p => p.key === picked) ?? null;

  if (floors.length === 0) return <p className="console-none">아직 지도가 준비되지 않았어요.</p>;
  return (
    <div className="venue-map">
      {floors.length > 1 && (
        <div className="venue-segment" role="group" aria-label="층 선택">
          {floors.map((f, i) => (
            <button key={i} type="button" aria-pressed={floor === i} className={floor === i ? 'on' : ''}
              onClick={() => { setFloor(i); setPicked(null); }}>{f.name}</button>
          ))}
        </div>
      )}
      {lists.length > 0 && (
        <div className="venue-chips" role="group" aria-label="표시할 장소">
          <button type="button" aria-pressed={filter === null} className={filter === null ? 'on' : ''}
            onClick={() => setFilter(null)}>전체</button>
          {lists.map((list, l) => (
            <button key={list.id} type="button" aria-pressed={filter === list.id} className={filter === list.id ? 'on' : ''}
              onClick={() => { setFilter(list.id); setPicked(null); }}>
              <span className="venue-dot" style={{ background: PIN_COLORS[l % PIN_COLORS.length] }} />{list.title}
            </button>
          ))}
        </div>
      )}
      <div className={`venue-plan-box${zoomed ? ' zoomed' : ''}`}>
        {plan?.imageId ? (
          <div className="venue-plan">
            <img src={venueImageUrl(sessionId, token, plan.imageId)} alt={`${plan.name} 지도`} />
            {shown.map(p => (
              <button key={p.key} type="button" className={`venue-pin${p.key === picked ? ' on' : ''}`}
                style={{ left: `${p.x}%`, top: `${p.y}%`, background: p.color }}
                aria-label={`${p.listTitle} · ${p.name}`} aria-pressed={p.key === picked}
                onClick={() => setPicked(p.key)}>{p.mark}</button>
            ))}
          </div>
        ) : <p className="console-none">이 층의 지도는 아직 없어요.</p>}
      </div>
      {plan?.imageId && (
        <button type="button" className="btn-tiny venue-zoom" onClick={() => setZoomed(z => !z)}>
          {zoomed ? '지도 작게 보기' : '지도 크게 보기'}</button>
      )}
      {current && (
        <section className="venue-picked" aria-live="polite">
          <span className="venue-pin-mark" style={{ background: current.color }}>{current.mark}</span>
          <span>
            <small style={{ color: current.color }}>{current.listTitle}</small>
            <b>{current.name}</b>
            {current.where && <span>{current.where}</span>}
          </span>
        </section>
      )}
      {lists.length > 0 && shown.length === 0 && <p className="console-none">이 층에는 표시할 곳이 없어요.</p>}
    </div>
  );
}

// ---- A list of places ----

function VenuePlaces({ section, map, onMap }: {
  section: Section<'PLACES'>; map: Section<'MAP'> | undefined; onMap: (mapId: number, index: number) => void;
}) {
  const [only, setOnly] = useState<string[]>([]);
  const places = section.content.places.map((place, index) => ({ place, index }))
    .filter(({ place }) => only.every(tag => place.tags.includes(tag)));
  const floorName = (floor: number | null) => (floor === null ? null : map?.content.floors[floor]?.name ?? null);
  // Grouped by floor where there is a map to name them, in the map's order.
  const groups = new Map<string, typeof places>();
  for (const entry of [...places].sort((a, b) => (a.place.floor ?? 99) - (b.place.floor ?? 99))) {
    const name = floorName(entry.place.floor) ?? '';
    groups.set(name, [...(groups.get(name) ?? []), entry]);
  }
  return (
    <div className="venue-places">
      {section.content.tags.length > 0 && (
        <div className="venue-chips" role="group" aria-label="거르기">
          {section.content.tags.map(tag => (
            <button key={tag} type="button" aria-pressed={only.includes(tag)} className={only.includes(tag) ? 'on' : ''}
              onClick={() => setOnly(current => current.includes(tag) ? current.filter(t => t !== tag) : [...current, tag])}>
              {tag}만</button>
          ))}
        </div>
      )}
      {[...groups.entries()].map(([name, entries]) => (
        <section key={name || 'none'} className="venue-place-group">
          {name && <h3>{name}</h3>}
          {entries.map(({ place, index }) => (
            <article key={index} className="venue-place">
              <div>
                <b>{place.name}</b>
                {place.where && <span>{place.where}</span>}
                {place.tags.length > 0 && (
                  <span className="venue-tags">{place.tags.map(tag => <small key={tag}>{tag}</small>)}</span>
                )}
              </div>
              {map && place.x !== null && (
                <button type="button" className="btn-tiny" onClick={() => onMap(map.id, index)}>
                  <Icon path={KIND_ICON.MAP} size={14} /> 지도</button>
              )}
            </article>
          ))}
        </section>
      ))}
      {places.length === 0 && <p className="console-none">{section.content.places.length === 0 ? '아직 등록된 곳이 없어요.' : '조건에 맞는 곳이 없어요.'}</p>}
    </div>
  );
}

// ---- Timetable ----

function VenueTimetable({ table }: { table: Section<'TIMETABLE'>['content'] }) {
  const [day, setDay] = useState(() => openingDay(table));
  const [stage, setStage] = useState<number | null>(null);
  const date = today();
  const hm = nowHm();
  const items = table.items.map((item, index) => ({ item, state: stateOf(table, index, date, hm) }))
    .filter(({ item }) => item.day === day && (stage === null || item.stage === stage));
  if (table.days.length === 0) return <p className="console-none">아직 일정이 없어요.</p>;
  return (
    <div className="venue-timetable">
      {table.days.length > 1 && (
        <div className="venue-segment" role="group" aria-label="날짜 선택">
          {table.days.map((d, i) => (
            <button key={i} type="button" aria-pressed={day === i} className={day === i ? 'on' : ''} onClick={() => setDay(i)}>
              <b>{d.label}</b>{d.date && <small>{d.date === date ? '오늘' : d.date.slice(5).replace('-', '.')}</small>}
            </button>
          ))}
        </div>
      )}
      {table.stages.length > 1 && (
        <div className="venue-chips" role="group" aria-label="무대 선택">
          <button type="button" aria-pressed={stage === null} className={stage === null ? 'on' : ''} onClick={() => setStage(null)}>전체</button>
          {table.stages.map((name, i) => (
            <button key={i} type="button" aria-pressed={stage === i} className={stage === i ? 'on' : ''} onClick={() => setStage(i)}>{name}</button>
          ))}
        </div>
      )}
      <ol className="venue-items">
        {items.map(({ item, state }, i) => (
          <li key={i} className={`is-${state}`}>
            <span className="venue-item-time"><b>{item.start}</b>{item.end && <small>{item.end}</small>}</span>
            <span className="venue-item-card">
              <span className="venue-item-tags">
                {item.stage !== null && table.stages[item.stage] && <small className="venue-stage">{table.stages[item.stage]}</small>}
                {state === 'now' && <span className="venue-live">진행 중</span>}
              </span>
              <b>{item.title}</b>
              {item.note && <span>{item.note}</span>}
            </span>
          </li>
        ))}
      </ol>
      {items.length === 0 && <p className="console-none">이 날에는 일정이 없어요.</p>}
    </div>
  );
}
