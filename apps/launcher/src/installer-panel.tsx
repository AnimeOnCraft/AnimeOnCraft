import { useEffect, useRef, useState } from 'react';
import { Button } from '@animeoncraft/ui';
import { InstallerApi, type InstallerInput, type InstallerSnapshot, type LocalInstanceState } from './installer-api.js';
import { native } from './native.js';

const api = new InstallerApi();
const messages: Record<InstallerSnapshot['status'], string> = {
  idle: 'Установщик готов к проверке.', unavailable: 'Сборка для установки пока не опубликована.',
  repository_unavailable: 'Не удалось проверить подписанные метаданные сборки. Повторите проверку; изменение файлов пока недоступно.',
  checking: 'Проверяем состояние сборки…', downloading: 'Загружаем подтверждённые файлы…',
  preparing: 'Подготавливаем файлы…', applying: 'Применяем изменения…', recovering: 'Восстанавливаем незавершённую операцию…',
  completed: 'Операция завершена. Разрешение на запуск игры проверяется отдельно.', cancelled: 'Операция отменена до применения изменений.',
  recovery_required: 'Незавершённая операция требует восстановления. Новая установка заблокирована.',
  awaiting_confirmation: 'План проверен. Подтвердите изменения перед установкой.',
  blocked: 'Локальные данные установки не прошли проверку. Изменение файлов заблокировано.',
  plan_changed: 'Файлы или состояние установки изменились после проверки. Подготовьте новый план.',
};
export function InstallerView({ state, localState = null, pending = false, error = '', onAction, onRefresh }: {
  state: InstallerSnapshot | null; localState?: LocalInstanceState | null; pending?: boolean; error?: string;
  onAction: (input: InstallerInput) => void; onRefresh: () => void;
}) {
  const locked = pending || !!error || !state || state.busy || state.status === 'blocked' || (!!localState && localState.status !== 'ready') || (native && !localState);
  const unavailable = state?.status === 'unavailable' || state?.status === 'repository_unavailable';
  const recovery = state?.status === 'recovery_required';
  const plan = state?.plan;
  return <section className="launcher-section" data-testid="installer"><h3>Установка и восстановление</h3>
    <p role="status">{state ? messages[state.status] : native ? 'Получаем состояние установщика…' : 'Установка будет доступна в Windows-приложении.'}</p>
    {error && <p role="alert">{error}</p>}
    {localState && <p role={localState.status === 'ready' ? 'status' : 'alert'} data-testid="installer-local-state">{localState.status === 'ready' ? 'Локальный каталог экземпляра подготовлен.' : localState.status === 'busy' ? 'Каталог занят другим процессом. Повторно откройте лаунчер после его завершения.' : localState.status === 'blocked' ? 'Не удалось подтвердить локальный каталог. Изменение файлов заблокировано; данные сохранены.' : 'Локальное хранилище поддерживается только в Windows на NTFS.'}</p>}
    {plan && <div className="dashboard-panel"><h4>{plan.summary.mode === 'repair' ? 'План исправления файлов' : 'План установки'}</h4>
      <dl className="detail-grid"><div><dt>Добавить</dt><dd>{plan.summary.install}</dd></div><div><dt>Заменить</dt><dd>{plan.summary.replace}</dd></div><div><dt>Убрать из сборки</dt><dd>{plan.summary.remove}</dd></div><div><dt>Сохранить без изменений</dt><dd>{plan.summary.reuse}</dd></div><div><dt>Скопировать, МиБ</dt><dd>{Math.ceil(plan.summary.staging_bytes / 1024 ** 2)}</dd></div><div><dt>Требуется места с резервом, МиБ</dt><dd>{Math.ceil(plan.summary.required_space / 1024 ** 2)}</dd></div></dl>
      <p className="muted">Заменяемые файлы сохраняются в резервной копии. Перед применением план будет проверен ещё раз.</p>
      <div className="actions"><Button type="button" disabled={locked} onClick={() => onAction({ action: 'confirm', plan_id: plan.plan_id })}>Подтвердить изменения</Button><Button type="button" disabled={locked} onClick={() => onAction({ action: 'discard', plan_id: plan.plan_id })}>Отказаться от плана</Button></div></div>}
    {state?.progress && <><progress aria-label="Выполненные операции текущего шага" value={state.progress.completed} max={state.progress.total} /><p className="muted">Операций на текущем шаге: {state.progress.completed} из {state.progress.total}</p></>}
    {state?.cancel_requested && <p role="status">{state.busy ? 'Отмена запрошена. Дождитесь безопасного завершения операции.' : state.status === 'completed' ? 'Изменения уже применялись при запросе отмены; операция завершена.' : ''}</p>}
    <div className="actions">
      <Button type="button" disabled={locked || recovery || !!plan} onClick={() => onAction({ action: 'check' })}>Проверить сборку</Button>
      <Button type="button" disabled={locked || unavailable || recovery || !!plan} onClick={() => onAction({ action: 'install' })}>Установить сборку</Button>
      <Button type="button" disabled={locked || unavailable || recovery || !!plan} onClick={() => onAction({ action: 'repair' })}>Исправить файлы</Button>
      <Button type="button" disabled={locked || !recovery} onClick={() => onAction({ action: 'recover' })}>Восстановить операцию</Button>
      {state?.busy && <Button type="button" disabled={pending || !!error || state.cancel_requested} onClick={() => onAction({ action: 'cancel', session_id: state.session_id, job_id: state.job_id })}>Отменить операцию</Button>}
      <Button type="button" disabled={!native || pending} onClick={onRefresh}>Обновить состояние установщика</Button>
    </div><p className="muted">Установка не предоставляет игровой доступ. Запуск игры пока недоступен.</p>
  </section>;
}
export function InstallerPanel() {
  const [state, setState] = useState<InstallerSnapshot | null>(null), [pending, setPending] = useState(false), [error, setError] = useState('');
  const [localState, setLocalState] = useState<LocalInstanceState | null>(null);
  const sequence = useRef(0), alive = useRef(false);
  useEffect(() => { alive.current = true; return () => { alive.current = false; sequence.current++; }; }, []);
  useEffect(() => {
    if (!native || pending || state?.busy === false) return;
    let active = true, timer: ReturnType<typeof setTimeout> | undefined;
    async function read() {
      const id = ++sequence.current;
      try {
        const [value, local] = await Promise.all([api.snapshot(), api.localState()]);
        if (active && id === sequence.current) { setState(value); setLocalState(local); setError(''); if (value.busy) timer = setTimeout(() => { void read(); }, 500); }
      } catch {
        if (active && id === sequence.current) setError('Не удалось получить состояние установщика. Обновите состояние перед следующей операцией.');
      }
    }
    void read();
    return () => { active = false; clearTimeout(timer); };
  }, [pending, state?.busy]);
  function run(input?: InstallerInput) {
    if (!native || pending) return;
    const id = ++sequence.current; setPending(true);
    void Promise.all([input ? api.action(input) : api.snapshot(), api.localState()]).then(([value, local]) => {
      if (alive.current && id === sequence.current) { setState(value); setLocalState(local); setError(''); }
    }).catch(() => {
      if (alive.current && id === sequence.current) setError('Не удалось выполнить действие. Обновите состояние установщика; повторная операция сейчас заблокирована.');
    }).finally(() => { if (alive.current && id === sequence.current) setPending(false); });
  }
  return <InstallerView state={state} localState={localState} pending={pending} error={error} onAction={run} onRefresh={() => run()} />;
}
