import { useState } from 'react';
import { Link } from 'react-router-dom';
import { createLinks } from '../api';

export default function CreateLink() {
  const [url, setUrl] = useState('');
  const [title, setTitle] = useState('');
  const [recipients, setRecipients] = useState<string[]>(['']);
  const [maxViews, setMaxViews] = useState('');
  const [expiresIn, setExpiresIn] = useState('7');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<{ shortCode: string; recipientNames: string | null }[] | null>(null);
  const [error, setError] = useState('');
  const [invalidRecipient, setInvalidRecipient] = useState<number | null>(null);
  const [invalidTitle, setInvalidTitle] = useState(false);
  const [copyMessage, setCopyMessage] = useState('');

  const validateRecipients = () => {
    setError('');
    setInvalidRecipient(null);
    const contacts = recipients.map(value => value.trim());
    const normalized = contacts.map(value => value.includes('@') ? value.toLowerCase() : value.replace(/-/g, ''));
    const invalid = contacts.findIndex(value => value.length > 254 || (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value) && !/^(?:[0-9]{11}|[0-9]{3}-[0-9]{4}-[0-9]{4})$/.test(value)));
    if (invalid !== -1) {
      setInvalidRecipient(invalid);
      setError(`수신자 ${invalid + 1}: 이메일 또는 전화번호를 입력해 주세요.`);
      document.getElementById(`recipient-${invalid}`)?.focus();
      return false;
    }
    const duplicate = normalized.findIndex((value, index) => normalized.indexOf(value) !== index);
    if (duplicate !== -1) {
      setInvalidRecipient(duplicate);
      setError(`수신자 ${duplicate + 1}: 중복된 수신자가 있습니다.`);
      document.getElementById(`recipient-${duplicate}`)?.focus();
      return false;
    }
    return true;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (loading || !validateRecipients()) return;
    if (!title.trim()) {
      setInvalidTitle(true);
      setError('링크 제목을 1글자 이상 입력해 주세요.');
      document.getElementById('link-title')?.focus();
      return;
    }
    setInvalidTitle(false);
    const contacts = recipients.map(value => value.trim());
    setLoading(true);
    try {
      const days = parseInt(expiresIn) || 7;
      const expiresAt = new Date(Date.now() + days * 24 * 3600000)
        .toISOString()
        .replace('T', ' ')
        .substring(0, 19);

      const link = await createLinks({
        originalUrl: url,
        title: title || undefined,
        expiresAt,
        recipients: contacts,
        maxViews: maxViews ? parseInt(maxViews) : undefined,
      });
      setResult(link);
    } catch (err) {
      const reason = err instanceof TypeError ? '네트워크가 지연되고 있습니다. 잠시 후 다시 시도해 주세요.'
        : err instanceof Error ? err.message : '링크 생성에 실패했습니다. 입력 내용을 확인한 뒤 다시 시도해 주세요.';
      setError(`${reason}\n실패가 지속되면 관리자에게 문의 부탁드립니다.`);
    } finally {
      setLoading(false);
    }
  };

  if (result) {
    return (
      <section className="page-section">
        <div className="result-card">
          <div className="result-icon">&#x2713;</div>
          <h2>보호 링크 {result.length}개가 생성되었습니다</h2>
          {result.map((link, index) => (
            <div className="result-code" key={link.shortCode}>
              <label>수신자 {index + 1}: {link.recipientNames}</label>
              <div className="code-display">
                <code>{window.location.origin}/s/{link.shortCode}</code>
                <button type="button" onClick={async () => {
                  try { await navigator.clipboard.writeText(`${window.location.origin}/s/${link.shortCode}`); setCopyMessage(`수신자 ${index + 1}의 링크를 복사했습니다.`); }
                  catch { setCopyMessage('복사하지 못했습니다. 링크를 직접 선택해서 복사해 주세요.'); }
                }}>복사</button>
              </div>
            </div>
          ))}
          <p role="status">{copyMessage}</p>
          <p className="result-hint">각 링크를 표시된 수신자에게 전달하세요. 이메일·문자는 자동 발송되지 않습니다. 먼저 패스키를 등록한 사람에게 귀속됩니다.</p>
          <div className="result-actions">
            <Link className="btn-primary" to="/manage">링크 관리로 이동</Link>
            <button className="btn-secondary" onClick={() => { setResult(null); setUrl(''); setTitle(''); setInvalidTitle(false); setRecipients(['']); setCopyMessage(''); setMaxViews(''); }}>
              새 링크 만들기
            </button>
          </div>
        </div>
      </section>
    );
  }

  return (
    <section className="page-section">
      <div className="section-header">
        <p className="eyebrow"><span></span> CREATE LINK</p>
        <h2>보호 링크 만들기</h2>
        <p className="section-desc">같은 원본 링크에 대해 수신자별 보호 링크를 한 번에 만듭니다.</p>
      </div>
      <p className="passkey-notice">수신자는 처음 링크를 열 때 이메일 또는 휴대폰 번호를 입력한 뒤 패스키를 등록합니다. 이후에는 등록한 패스키로만 열 수 있습니다.</p>
      <form className="create-form" onSubmit={handleSubmit}>
        <div className="field">
          <label>원본 링크 *</label>
          <input
            type="url"
            required
            placeholder="https://example.com/my-document"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="link-title">링크 제목 * <span className="field-hint">최대 50자</span></label>
          <input
            id="link-title"
            type="text"
            required
            minLength={1}
            maxLength={50}
            aria-invalid={invalidTitle}
            onInvalid={() => { setInvalidTitle(true); setError('링크 제목을 1글자 이상 입력해 주세요.'); }}
            placeholder="예: Q3 브랜드 리뉴얼 제안서"
            value={title}
            onChange={(e) => { setTitle(e.target.value); if (e.target.value.trim()) { setInvalidTitle(false); if (invalidRecipient === null) setError(''); } }}
          />
        </div>
        <div className="field-row">
          <div className="field">
            <label>만료 기간 (일)</label>
            <input
              type="number"
              min="1"
              placeholder="7"
              value={expiresIn}
              onChange={(e) => setExpiresIn(e.target.value)}
            />
          </div>
          <div className="field">
            <label>최대 열람 수</label>
            <input
              type="number"
              min="0"
              placeholder="0 = 제한 없음"
              value={maxViews}
              onChange={(e) => setMaxViews(e.target.value)}
            />
          </div>
        </div>
        <div>
          <fieldset className="field recipient-fields" disabled={loading}>
            <legend>수신자 <span className="recipient-limit">최대 100명</span></legend>
            <p className="recipient-help">이메일 또는 전화번호를 입력해 주세요. 수신자마다 별도의 보호 링크가 생성됩니다.</p>
            {recipients.map((contact, index) => (
              <div className="recipient-row" key={index}>
                <label className="recipient-label" htmlFor={`recipient-${index}`}><span className="recipient-sr-only">수신자 </span>{index + 1}</label>
                <input id={`recipient-${index}`} type="text" required maxLength={254}
                  aria-invalid={invalidRecipient === index}
                  aria-describedby={invalidRecipient === index ? `recipient-error-${index}` : undefined}
                  onInvalid={() => { setInvalidRecipient(index); setError('수신자 이메일 또는 전화번호를 입력해 주세요.'); }}
                  placeholder="이메일 또는 전화번호" value={contact}
                  onChange={e => { setRecipients(recipients.map((value, i) => i === index ? e.target.value : value)); if (invalidRecipient === index) { setInvalidRecipient(null); setError(''); } }} />
                <button type="button" className="recipient-remove" disabled={recipients.length === 1}
                  aria-label={`수신자 ${index + 1} 삭제`} onClick={() => { setRecipients(recipients.filter((_, i) => i !== index)); setInvalidRecipient(null); setError(''); }}><span aria-hidden="true">×</span></button>
                {invalidRecipient === index && <p className="recipient-error" id={`recipient-error-${index}`} role="alert">{error}</p>}
              </div>
            ))}
            <button type="button" className="recipient-add" disabled={recipients.length >= 100}
              onClick={() => { if (validateRecipients()) { setRecipients([...recipients, '']); requestAnimationFrame(() => document.getElementById(`recipient-${recipients.length}`)?.focus()); } }}><span aria-hidden="true">＋</span> 수신자 추가</button>
          </fieldset>
        </div>
        {error && invalidRecipient === null && <p className="error-text" role="alert">{error}</p>}
        <button className="btn-primary" type="submit" disabled={loading}>
          {loading ? '생성 중...' : '보호 링크 생성'}
        </button>
      </form>
    </section>
  );
}
