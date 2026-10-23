export type Page = 'home' | 'clans' | 'map' | 'profile' | 'news' | 'settings' | 'admin';
export interface Route { page: Page; key?: string }
export const pages: { page: Page; label: string }[] = [{ page: 'home', label: 'Главная' }, { page: 'clans', label: 'Кланы' }, { page: 'map', label: 'Карта' }, { page: 'profile', label: 'Профиль' }, { page: 'news', label: 'Новости' }, { page: 'settings', label: 'Настройки' }];
export function readRoute(hash: string): Route {
  const [page, key] = hash.replace(/^#\/?/, '').split('/');
  if (page !== 'admin' && !pages.some(item => item.page === page)) return { page: 'home' };
  if (key && page === 'clans' && /^[0-9a-f-]{36}$/i.test(key)) return { page, key };
  if (key && page === 'news' && key.length <= 100 && /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(key)) return { page, key };
  return { page: page as Page };
}
