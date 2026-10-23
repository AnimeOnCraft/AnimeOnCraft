import { useEffect, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { native } from './native.js';
import { accountSnapshot } from './account-api.js';
import { accountChangedEvent } from './account-navigation.js';
import { readGameProfile } from './game-profile-api.js';
import { LauncherIcon } from './launcher-icon.js';
import { SkinModelPreview, type PreviewSkin } from './skin-model-preview.js';

export function CharacterPreview() {
  const [skin, setSkin] = useState<PreviewSkin | null>(null), [status, setStatus] = useState('Загружаем персонажа…'), [name, setName] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    async function load() {
      if (!native) { setStatus('Персонаж доступен в Windows-приложении'); return; }
      try {
        const account = await accountSnapshot(); if (!active) return;
        if (account.state !== 'SIGNED_IN') { setSkin(null); setName(null); setStatus('Войдите, чтобы увидеть своего персонажа'); return; }
        const [result, profile] = await Promise.all([invoke<{ skin: PreviewSkin | null }>('skin_read'), readGameProfile().catch(() => null)]);
        if (!active) return;
        setSkin(result.skin); setName(profile?.profile?.game_name ?? null);
        setStatus(result.skin ? 'Сохранённый скин' : 'Загрузите PNG-скин в профиле');
      } catch { if (active) setStatus('Не удалось загрузить скин. Откройте профиль и повторите попытку.'); }
    }
    void load(); window.addEventListener(accountChangedEvent, load);
    return () => { active = false; window.removeEventListener(accountChangedEvent, load); };
  }, []);
  return <section className="panel character-card" aria-label="Предпросмотр персонажа">
    <div className="block-head"><h3 className="panel-title">Твой персонаж</h3>{name && <span className="name-badge">{name}</span>}</div>
    <SkinModelPreview skin={skin} status={status} controls={false} />
    <a className="ghost-button wide" href="#profile"><LauncherIcon name="settings" size={16} />Настроить персонажа</a>
  </section>;
}
