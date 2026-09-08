interface Props {
  title: string;
  desc: string;
}

export default function Placeholder({ title, desc }: Props) {
  return (
    <section className="page-section">
      <div className="section-header">
        <h2>{title}</h2>
        <p className="section-desc">{desc}</p>
      </div>
      <p className="empty">준비 중입니다.</p>
    </section>
  );
}
