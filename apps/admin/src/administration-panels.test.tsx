import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { LoginPanel } from './auth-panel.js';
import { ClanManagement } from './clan-management.js';
import { NewsPanel, NewsEditor } from './administration-panels.js';
afterEach(()=>vi.unstubAllGlobals());
describe('administrative rendering',()=>{
  it('disables credential submission on the HTTP preview',()=>{
    vi.stubGlobal('location',{protocol:'http:'});const html=renderToStaticMarkup(<LoginPanel onLogin={()=>{}}/>);expect(html).toContain('Для входа требуется HTTPS');expect(html).toContain('<fieldset disabled=""');expect(html).not.toContain('localStorage');
  });
  it('offers MFA-ready login on HTTPS without rendered auth tokens',()=>{
    vi.stubGlobal('location',{protocol:'https:'});const html=renderToStaticMarkup(<LoginPanel onLogin={()=>{}}/>);expect(html.toLowerCase()).toContain('autocomplete="current-password"');expect(html).not.toContain('<fieldset disabled=""');expect(html).not.toMatch(/access_token|refresh_token/);
  });
  it('starts protected clan management without preloaded privileged data',()=>{
    const html=renderToStaticMarkup(<ClanManagement actorId="fixture" canRemove={()=>false}/>);expect(html).toContain('Загрузить панель');expect(html).not.toContain('Назначить менеджера');expect(html).not.toContain('Создать клан');
  });
  it('starts news as an unpublished draft with an escaped text preview',()=>{
    const html=renderToStaticMarkup(<NewsPanel/>);expect(html).toContain('Создать черновик');expect(html).toContain('HTML запрещён');expect(html).not.toContain('dangerouslySetInnerHTML');expect(html).not.toContain('Опубликовать сохранённый текст');
  });
  it('escapes stored news text and titles without creating executable HTML',()=>{
    const html=renderToStaticMarkup(<NewsEditor news={{id:'fixture',slug:'fixture',title:'<script>attack</script>',body_markdown:'<img src=x onerror=alert(1)>',published_at:null,archived_at:null,version:'0',created_at:'2026-10-10T00:00:00Z'}} disabled={false} reason="fixture" submit={async()=>{}}/>);
    expect(html).toContain('&lt;script&gt;');expect(html).toContain('&lt;img');expect(html).not.toContain('<script>');expect(html).not.toContain('<img');
  });
});
