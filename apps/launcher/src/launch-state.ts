import { vpnHint } from './connection-hint.js';
import type { ServerView } from './game-server.js';
import { serverLaunchMessage } from './game-server.js';
import type { PreparationSnapshot } from './game-preparation.js';
import type { SimpleUpdateState } from './simple-update.js';

export interface GameSnapshot { state: 'STOPPED' | 'STARTING' | 'RUNNING' | 'EXITED' | 'FAILED' | 'PREPARING'; reason: string; can_launch: boolean }

export const launchMessages: Record<string, string> = {
  LAUNCHER_UPDATE_REQUIRED: 'Вышла новая версия лаунчера. Обновите его и заново запустите Minecraft.',
  LAUNCHER_UPDATE_CHECK_FAILED: 'Не удалось проверить обновления. Проверьте связь и повторите попытку.',
  RUNTIME_PREPARATION_REQUIRED: 'Предыдущая подготовка не завершилась. Нажмите «Установить сборку», чтобы продолжить.',
  GAME_SERVER_UNAVAILABLE: `Игровой сервер не отвечает. ${vpnHint}`,
  GAME_SERVER_INCOMPATIBLE: 'Версия игрового сервера отличается от Minecraft 1.21.1.',
  GAME_SERVER_INVALID_RESPONSE: `Игровой сервер вернул неподдерживаемый ответ. ${vpnHint}`,
  RUNTIME_MOD_UPDATE_REQUIRED: 'Вышло обновление игровой сборки. Нажмите «Обновить сборку», чтобы установить совместимые моды.',
  RUNTIME_NOT_CONFIGURED: 'Игровая сборка ещё не установлена на этом компьютере.',
  RUNTIME_INVALID: 'Файлы игрового запуска недоступны. Нужна повторная подготовка сборки.',
  GAME_PROFILE_REQUIRED: 'Выберите игровой ник в профиле.',
  GAME_ACCESS_REQUIRED: 'Аккаунту сейчас не разрешён вход. Проверьте игровой доступ во вкладке «Кланы».',
  JAVA_21_REQUIRED: 'Для сборки нужна Java 21.',
  JAVA_UNAVAILABLE: 'Не удалось запустить Java 21.',
  GAME_START_FAILED: 'Не удалось открыть Minecraft.',
  GAME_PROCESS_FAILED: 'Minecraft завершился с ошибкой. Подробности находятся в игровом журнале.',
  BRIDGE_UNAVAILABLE: 'Не удалось подготовить подключение к игровому серверу.',
  GAME_ALREADY_RUNNING: 'Minecraft уже запущен.',
};

const installReasons = new Set(['RUNTIME_NOT_CONFIGURED', 'RUNTIME_PREPARATION_REQUIRED', 'RUNTIME_INVALID']);
const stageNames: Record<string, string> = { CHECKING: 'Проверяем сборку', MODS: 'Загружаем моды', MINECRAFT: 'Подготавливаем Minecraft', LOADER: 'Устанавливаем NeoForge', FILES: 'Проверяем библиотеки', VERIFYING: 'Проверяем готовность', DONE: 'Сборка готова' };

export interface LaunchInput {
  native: boolean;
  update: SimpleUpdateState | null;
  updatePending: boolean;
  account: 'loading' | 'signed-out' | 'password' | 'signed-in';
  game: GameSnapshot | null;
  preparation: PreparationSnapshot | null;
  preparing: boolean;
  launching: boolean;
  server: ServerView;
}
export type LaunchAction = 'install-update' | 'check-update' | 'cancel-update' | 'sign-in' | 'change-password' | 'choose-name' | 'prepare' | 'launch' | 'none';
export interface LaunchView {
  action: LaunchAction;
  label: string;
  /** Short line under the main heading of the hero. */
  detail: string;
  enabled: boolean;
  tone: 'play' | 'update' | 'neutral';
  progress: { value: number | null; max: number } | null;
}

const view = (action: LaunchAction, label: string, detail: string, enabled: boolean, tone: LaunchView['tone'] = 'play', progress: LaunchView['progress'] = null): LaunchView => ({ action, label, detail, enabled, tone, progress });
const mib = (bytes: number) => (bytes / 1024 ** 2).toFixed(1);

/**
 * Decide what the single main button of the home screen does.
 * Order matters: a published launcher update is mandatory (the server accepts only the current client),
 * then the game files, then the account, then the actual launch.
 */
export function launchView(input: LaunchInput): LaunchView {
  if (!input.native) return view('none', 'Играть', 'Запуск доступен в Windows-приложении.', false);
  const update = input.update;
  if (update?.status === 'downloading') {
    const total = update.total;
    return view('cancel-update', 'Отменить загрузку', `Скачиваем обновление ${update.latest_version ?? ''}: ${mib(update.downloaded)}${total ? ` из ${mib(total)}` : ''} МиБ.`, !input.updatePending, 'update', { value: total ? Math.min(update.downloaded, total) : null, max: total ?? 1 });
  }
  if (update?.status === 'installing') return view('none', 'Устанавливаем…', 'Устанавливаем обновление. Лаунчер закроется и запустится снова.', false, 'update', { value: null, max: 1 });
  if (update?.status === 'available') return view('install-update', 'Обновление', `Доступна версия ${update.latest_version}. Обновите лаунчер, чтобы играть: настройки и сохранения останутся.`, !input.updatePending, 'update');
  if (update?.status === 'error' && update.reason !== null && ['DOWNLOAD_FAILED', 'INSTALL_FAILED', 'RELEASE_INVALID', 'TOO_LARGE'].includes(update.reason)) return view('check-update', 'Повторить обновление', 'Обновление не удалось загрузить или установить. Повторите попытку.', !input.updatePending, 'update');
  if (update?.latest_version) return view('check-update', 'Обновление обязательно', `Вышла версия ${update.latest_version}. Проверьте обновление и установите его перед игрой.`, !input.updatePending, 'update');
  if (update?.status === 'error' && update.reason === 'NETWORK') return view('check-update', 'Проверить обновление', 'Не удалось проверить актуальную версию. Проверьте связь и повторите попытку.', !input.updatePending, 'update');
  if (update?.status === 'checking' || (update === null && input.updatePending)) return view('none', 'Проверяем обновления…', 'Проверяем, не вышла ли новая версия лаунчера.', false, 'neutral', { value: null, max: 1 });

  if (input.preparing || input.preparation?.state === 'RUNNING') {
    const prep = input.preparation;
    const stage = stageNames[prep?.stage ?? 'CHECKING'] ?? stageNames.CHECKING!;
    return view('none', prep?.total ? `Сборка ${Math.round((prep.completed / prep.total) * 100)}%` : 'Подготовка…', `${stage}${prep?.total ? ` · ${prep.completed} / ${prep.total}` : ''}`, false, 'update', { value: prep?.total ? prep.completed : null, max: prep?.total || 1 });
  }
  if (input.account === 'loading') return view('none', 'Играть', 'Проверяем аккаунт…', false);
  if (input.account === 'signed-out') return view('sign-in', 'Войти', 'Войдите в аккаунт, чтобы начать игру.', true);
  if (input.account === 'password') return view('change-password', 'Сменить пароль', 'Перед игрой задайте собственный пароль вместо временного.', true);

  const game = input.game;
  if (!game) return view('none', 'Играть', 'Проверяем подготовленную сборку…', false);
  if (game.state === 'RUNNING') return view('none', 'Minecraft открыт', 'Minecraft запущен. Для выхода закройте игровое окно.', false);
  if (input.launching || game.state === 'STARTING') return view('none', 'Запускаем…', 'Проверяем аккаунт и запускаем Minecraft…', false);
  if (!game.can_launch) {
    if (game.reason === 'RUNTIME_MOD_UPDATE_REQUIRED') return view('prepare', 'Обновить сборку', launchMessages.RUNTIME_MOD_UPDATE_REQUIRED!, true, 'update');
    if (installReasons.has(game.reason) || !game.reason) return view('prepare', 'Установить сборку', launchMessages[game.reason] ?? launchMessages.RUNTIME_NOT_CONFIGURED!, true, 'update');
    if (game.reason === 'GAME_PROFILE_REQUIRED') return view('choose-name', 'Выбрать ник', launchMessages.GAME_PROFILE_REQUIRED!, true);
    return view('none', 'Играть', launchMessages[game.reason] ?? launchMessages.RUNTIME_NOT_CONFIGURED!, false);
  }
  if (input.server.error || input.server.snapshot?.status !== 'AVAILABLE') return view('none', 'Играть', serverLaunchMessage(input.server), false);
  if (input.preparation?.state === 'FAILED') return view('launch', 'Играть', 'Последняя проверка сборки не удалась. Можно играть или повторить проверку.', true);
  return view('launch', 'Играть', 'Сборка готова. Разрешение на вход проверяется при подключении.', true);
}
