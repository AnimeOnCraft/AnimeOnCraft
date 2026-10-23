import { useEffect, useRef, useState } from 'react';
import { Button } from '@animeoncraft/ui';
import { native } from './native.js';
import { UpdateApi, type UpdateInput, type UpdateSnapshot } from './update-api.js';
const api = new UpdateApi();
const messages: Record<UpdateSnapshot['status'], string> = {
  unavailable: 'Источник обновлений лаунчера пока не опубликован.', idle: 'Можно проверить обновления лаунчера.',
  preparing: 'Загружаем и проверяем подписанное обновление…', awaiting_confirmation: 'Обновление проверено. Подтвердите установку.',
  cancelled: 'Проверка обновления отменена.', expired: 'Подтверждение устарело. Проверьте обновление ещё раз.',
  failed: 'Обновление не прошло проверку или подготовку. Установка не запущена.', current: 'Опубликованная версия уже установлена.',
  handing_off: 'Лаунчер закрывается для установки обновления. Дождитесь завершения установщика.',
};
export function UpdateView({ state, pending = false, error = '', onAction }: { state: UpdateSnapshot | null; pending?: boolean; error?: string; onAction: (input?: UpdateInput) => void }) {
  const locked = pending || !!error || !state;
  return <section className="launcher-section" data-testid="launcher-update"><h3>Обновление лаунчера</h3>
    <p role="status">{state ? state.reason === 'UPDATE_NOT_INSTALLED' ? 'Обновление доступно после установки Windows-приложения.' : messages[state.status] : native ? 'Получаем состояние обновления…' : 'Самообновление доступно в Windows-приложении.'}</p>
    {state && <p className="muted">Текущая версия: {state.current_version}</p>}{error && <p role="alert">{error}</p>}
    {state?.plan && <div className="dashboard-panel"><p>Новая версия: <strong>{state.plan.version}</strong>. Размер установщика: {Math.ceil(state.plan.bytes / 1024 ** 2)} МиБ.</p><p>Лаунчер завершит работу. Настройки и игровые данные сохраняются.</p><div className="actions"><Button type="button" disabled={locked} onClick={() => onAction({ action: 'confirm', plan_id: state.plan!.plan_id })}>Установить обновление</Button><Button type="button" disabled={locked} onClick={() => onAction({ action: 'discard', plan_id: state.plan!.plan_id })}>Отказаться от обновления</Button></div></div>}
    <div className="actions"><Button type="button" disabled={locked || !state?.source_configured || state.busy || !!state.plan || state.status === 'unavailable'} onClick={() => onAction({ action: 'check' })}>Проверить обновление лаунчера</Button>
      {state?.status === 'preparing' && <Button type="button" disabled={locked} onClick={() => onAction({ action: 'cancel', session_id: state.session_id, job_id: state.job_id })}>Отменить проверку обновления</Button>}
      <Button type="button" disabled={!native || pending} onClick={() => onAction()}>Обновить состояние обновления</Button></div></section>;
}
export function UpdatePanel() {
  const [state, setState] = useState<UpdateSnapshot | null>(null), [pending, setPending] = useState(false), [error, setError] = useState('');
  const alive = useRef(false), sequence = useRef(0);
  useEffect(() => { alive.current = true; return () => { alive.current = false; sequence.current++; }; }, []);
  useEffect(() => {
    if (!native || pending) return;
    let active = true, timer: ReturnType<typeof setTimeout> | undefined;
    async function read() {
      const id = ++sequence.current;
      try {
        const value = await api.snapshot();
        if (active && id === sequence.current) { setState(value); setError(''); if (value.busy || value.plan) timer = setTimeout(() => { void read(); }, 500); }
      } catch { if (active && id === sequence.current) setError('Не удалось получить состояние обновления. Обновите состояние перед следующей попыткой.'); }
    }
    void read(); return () => { active = false; clearTimeout(timer); };
  }, [pending]);
  function run(input?: UpdateInput) {
    if (!native || pending) return;
    const id = ++sequence.current; setPending(true);
    void (input ? api.action(input) : api.snapshot()).then(value => {
      if (alive.current && id === sequence.current) { setState(value); setError(''); }
    }).catch(() => { if (alive.current && id === sequence.current) setError('Не удалось выполнить действие. Обновите состояние перед следующей попыткой.'); }).finally(() => { if (alive.current && id === sequence.current) setPending(false); });
  }
  return <UpdateView state={state} pending={pending} error={error} onAction={run} />;
}
