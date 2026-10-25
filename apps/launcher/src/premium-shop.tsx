import { useCallback, useEffect, useRef, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { accountError } from './account-api.js';
import { LauncherIcon } from './launcher-icon.js';
interface Pack { code: string; crystals: number; rubles: number }
interface Order { id: string; pack: string; crystals: number; amount: number; currency: string; status: string; created_at: string }
interface Snapshot { wallet: { earned: number; paid: number; total: number }; packs: Pack[]; payments_enabled: boolean; orders: Order[] }
const STATUS: Record<string, string> = { PENDING: 'ожидает оплаты', SUCCEEDED: 'зачислено', CANCELED: 'отменён', REFUNDED: 'возврат' };
const ERRORS: Record<string, string> = { PAYMENTS_DISABLED: 'Оплата пока не подключена. Загляните позже.', BROWSER_UNAVAILABLE: 'Не удалось открыть браузер. Заказ создан, попробуйте ещё раз.' };
const OFFER = 'https://animeoncraft.github.io/offer.html';

/** Crystal shop: the launcher only starts a payment; crystals are credited after YooKassa confirms it. */
export function PremiumShop({ disabled, onBusy }: { disabled: boolean; onBusy: (v: boolean) => void }) {
  const [data, setData] = useState<Snapshot | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const generation = useRef(0);
  const fail = (e: unknown) => setError(typeof e === 'string' && ERRORS[e] ? ERRORS[e] : accountError(e));
  const refresh = useCallback(async () => { const current = ++generation.current; setError(''); try { const value = await invoke<Snapshot>('premium_read'); if (generation.current === current) setData(value); } catch (e) { if (generation.current === current) fail(e); } }, []);
  useEffect(() => { void refresh(); return () => { generation.current++; }; }, [refresh]);
  async function buy(pack: Pack) {
    const current = ++generation.current; setBusy(true); onBusy(true); setError(''); setNotice('');
    try { await invoke('premium_order', { input: { pack: pack.code } }); if (generation.current === current) setNotice(`Страница оплаты открыта в браузере. После оплаты ${pack.crystals} ◆ придут автоматически — нажмите «Обновить».`); void refresh(); }
    catch (e) { if (generation.current === current) fail(e); }
    finally { if (generation.current === current) setBusy(false); onBusy(false); }
  }
  if (!data) return error ? <p role="alert">{error}</p> : <p role="status" className="muted">Загружаем кристаллы…</p>;
  return <div className="premium-shop" data-testid="premium-shop">
    <h4 className="block-title">Кристаллы Разлома</h4>
    <p className="premium-balance" data-testid="premium-balance"><strong>{data.wallet.total} ◆</strong><span className="muted small">заработано {data.wallet.earned} · куплено {data.wallet.paid}</span></p>
    <p className="muted small">Кристаллы тратятся только на призыв «Сакура Разлома» (меню сервера → Призыв). Предметы нельзя купить напрямую, только выбить. Шансы и гарантии — в оферте.</p>
    <div className="cape-grid">
      {data.packs.map(p => <button key={p.code} type="button" className="cape-option premium-pack" data-testid={`premium-${p.code}`} disabled={disabled || busy || !data.payments_enabled} onClick={() => void buy(p)}>
        <span className="premium-gem" aria-hidden="true"><LauncherIcon name="sparkles" size={30} /></span><strong>{p.crystals} ◆</strong><small>{p.rubles.toLocaleString('ru-RU')} ₽</small>
      </button>)}
    </div>
    {!data.payments_enabled && <p className="muted small">Оплата пока не подключена.</p>}
    <p className="muted small">Покупая, вы принимаете оферту: {OFFER}. Неистраченные купленные кристаллы можно вернуть в течение 14 дней.</p>
    {data.orders.length > 0 && <ul className="premium-orders">{data.orders.slice(0, 8).map(o => <li key={o.id}><span>{new Date(o.created_at).toLocaleString('ru-RU')}</span><span>{o.crystals} ◆</span><span>{o.currency === 'XTR' ? `${o.amount} ⭐` : `${(o.amount / 100).toLocaleString('ru-RU')} ₽`}</span><span className={o.status === 'SUCCEEDED' ? 'ok-text' : 'muted'}>{STATUS[o.status] ?? o.status}</span></li>)}</ul>}
    <div className="button-row">
      <button type="button" className="icon-button" data-testid="premium-refresh" aria-label="Обновить баланс" title="Обновить баланс" disabled={disabled || busy} onClick={() => void refresh()}><LauncherIcon name="refresh" size={16} /></button>
    </div>
    {error && <p role="alert">{error}</p>}{notice && <p role="status" className="ok-text">{notice}</p>}
  </div>;
}
