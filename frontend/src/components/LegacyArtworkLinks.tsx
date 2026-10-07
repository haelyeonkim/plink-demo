import { useEffect, useState } from 'react';
import { mutate } from '../auth';
import type { ArtworkRecord } from '../types';

interface Pending { contentId: number; position: number; contentTitle: string; title: string; artist: string; ownerSub: string }
interface Candidate extends ArtworkRecord { contentTitle: string }
async function read(response: Response) {
  if (!response.ok) throw new Error('작품 연결 정보를 처리하지 못했어요. 다시 시도해 주세요.');
  return response.json();
}

export default function LegacyArtworkLinks() {
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState<{ items: Pending[]; total: number } | null>(null);
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<Pending | null>(null);
  const [search, setSearch] = useState('');
  const [candidatePage, setCandidatePage] = useState(0);
  const [candidates, setCandidates] = useState<{ items: Candidate[]; total: number } | null>(null);
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!open) return;
    let active = true;
    setPending(null); setError('');
    void fetch(`/api/admin/artworks/unlinked?page=${page}`).then(read)
      .then(data => { if (active) setPending(data); })
      .catch(err => { if (active) setError(err.message); });
    return () => { active = false; };
  }, [open, page, revision]);
  useEffect(() => {
    if (!selected) return;
    const controller = new AbortController();
    setCandidates(null);
    const timer = window.setTimeout(() => {
      void fetch(`/api/admin/artworks/candidates/${selected.contentId}?${new URLSearchParams({ search, page: String(candidatePage) })}`,
        { signal: controller.signal }).then(read)
        .then(data => { if (!controller.signal.aborted) setCandidates(data); })
        .catch(err => { if (!controller.signal.aborted) setError(err.message); });
    }, 200);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [selected, search, candidatePage]);

  async function connect(work: Candidate) {
    if (!selected || !window.confirm(`기존 링크의 “${selected.title}”을 원본 “${work.title}” (#${work.id})에 연결할까요? 판매 상태가 이 작품을 따라갑니다.`)) return;
    setBusy(true); setError('');
    try {
      await read(await mutate('/api/admin/artworks/associate', { method: 'POST',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({
          contentId: selected.contentId, position: selected.position, artworkId: work.id,
        }) }));
      setSelected(null); setRevision(value => value + 1);
    } catch (err) { setError(err instanceof Error ? err.message : '연결하지 못했어요.'); }
    finally { setBusy(false); }
  }
  return <details className="legacy-artwork-links" onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>기존 링크의 원본 작품 연결</summary>
    {open && <div className="tab-panel">
      <p className="hint-text">이전에 발급한 링크 중 원본 작품이 연결되지 않은 항목입니다. 같은 계정의 원본을 확인해 연결하면 판매 상태가 자동 반영됩니다.</p>
      {error && <p className="error-text" role="alert">{error}</p>}
      {pending ? <>
        <p>연결 필요 {pending.total}점</p>
        <ul className="entity-list">{pending.items.map(row => <li key={`${row.contentId}-${row.position}`}>
          <div className="entity-main"><strong>{row.title || '제목 없음'}</strong>
            <span className="entity-meta">{row.artist} · {row.contentTitle} · {row.ownerSub}</span></div>
          <button className="btn-tiny" disabled={busy} onClick={() => {
            setSelected(row); setSearch(row.title); setCandidatePage(0); setError('');
          }}>원본 선택</button>
        </li>)}</ul>
        {(pending.total > 20 || page > 0) && <nav className="admin-artwork-pagination" aria-label="미연결 작품 페이지">
          <button className="btn-secondary" disabled={page === 0 || busy} onClick={() => { setPage(page - 1); setSelected(null); }}>이전</button>
          <span>{page + 1}</span><button className="btn-secondary" disabled={(page + 1) * 20 >= pending.total || busy}
            onClick={() => { setPage(page + 1); setSelected(null); }}>다음</button>
        </nav>}
      </> : !error && <p role="status">연결 정보를 불러오고 있어요.</p>}
      {selected && <section className="legacy-artwork-picker" aria-label="원본 작품 선택">
        <h3>“{selected.title}”의 원본 작품 선택</h3>
        <div className="field"><label htmlFor="legacy-artwork-search">같은 계정의 작품 검색</label>
          <input id="legacy-artwork-search" maxLength={200} value={search}
            onChange={event => { setSearch(event.target.value); setCandidatePage(0); }} /></div>
        {candidates && <>
          {candidates.items.length === 0 && <p>일치하는 원본이 없습니다. 검색어를 변경해 주세요.</p>}
          <ul className="entity-list">{candidates.items.map(work => <li key={work.id}>
            <div className="entity-main"><strong>{work.title} · #{work.id}</strong>
              <span className="entity-meta">{work.artist} · {work.contentTitle}</span></div>
            <button className="btn-tiny" disabled={busy} onClick={() => void connect(work)}>이 작품에 연결</button>
          </li>)}</ul>
          <nav className="admin-artwork-pagination" aria-label="원본 후보 페이지">
            <button className="btn-secondary" disabled={candidatePage === 0} onClick={() => setCandidatePage(candidatePage - 1)}>이전</button>
            <span>{candidatePage + 1}</span><button className="btn-secondary" disabled={(candidatePage + 1) * 10 >= candidates.total}
              onClick={() => setCandidatePage(candidatePage + 1)}>다음</button>
          </nav>
        </>}
      </section>}
    </div>}
  </details>;
}
