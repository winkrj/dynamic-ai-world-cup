import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';
import { fixturePreview } from './api/fixtures';

function App() {
  return (
    <main>
      <header><span className="brand">WORLD CUP</span><span className="badge">개발 기반 · v0.1</span></header>
      <section className="intro">
        <p className="eyebrow">THINK LESS. PICK ONE.</p>
        <h1>고민은 짧게.<br />선택은 너답게.</h1>
        <p>후보를 준비하고, 둘 중 하나씩.<br />마지막에 남는 건 너의 선택.</p>
      </section>
      <section className="preview" aria-labelledby="preview-title">
        <div className="section-title"><h2 id="preview-title">취미 8강 · 계약 예제</h2><span>8 candidates</span></div>
        <p className="notice">화면 개발용 고정 예제입니다. AI 생성과 실제 플레이는 아직 연결되지 않았습니다.</p>
        <ol className="candidate-grid">
          {fixturePreview.candidates.map((candidate, index) => (
            <li key={candidate.id}><span className="number">{String(index + 1).padStart(2, '0')}</span><strong>{candidate.name}</strong><small>{candidate.tags.join(' · ')}</small></li>
          ))}
        </ol>
      </section>
      <footer>플레이 경험과 후보 품질, 두 영역에서 개발을 시작합니다.</footer>
    </main>
  );
}

createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>);
