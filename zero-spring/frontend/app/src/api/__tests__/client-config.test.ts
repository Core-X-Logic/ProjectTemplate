import { afterEach, describe, expect, it, vi } from 'vitest';

/**
 * `client.ts` içindeki base-URL guard'ının GERÇEKTEN ateşlediğini kanıtlar.
 *
 * Neden ayrı bir test: guard olmadan eksik bir `.env`'in sonucu **sessizdir**. Base URL boş
 * kalır, her istek göreli yola gider, Vite dev sunucusu 404 döner ve konsolda "neden hiçbir şey
 * yüklenmiyor" sorusundan başka ipucu olmaz. Şablonu ilk kez klonlayan birinin çarptığı ilk
 * duvar buydu.
 *
 * Ve neden guard'ı "test miyim" diye sorgulatmak yerine `vite.config.ts` içinde testlere gerçek
 * bir `VITE_API_BASE_URL` verildi: üretim kodunun test ortamını tanıması, guard'ın üretimde
 * bozulup testlerde fark edilmemesinin en kolay yoludur. Ama o çözümün bedeli, guard'ın
 * testlerde hiç çalışmaması — bu dosya tam olarak o boşluğu kapatır.
 */
describe('api client yapılandırması', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it('VITE_API_BASE_URL boşken dev modunda açık bir hatayla düşer', async () => {
    vi.resetModules();
    vi.stubEnv('VITE_API_BASE_URL', '');

    await expect(import('../client')).rejects.toThrow(/VITE_API_BASE_URL/);
  });

  it('hata mesajı ne yapılacağını söyler — sadece neyin eksik olduğunu değil', async () => {
    vi.resetModules();
    vi.stubEnv('VITE_API_BASE_URL', '');

    // Bir hata mesajının değeri, okuyanın bir sonraki adımı bilmesindedir.
    await expect(import('../client')).rejects.toThrow(/\.env/);
  });

  it('base URL verildiğinde modül sorunsuz yüklenir', async () => {
    vi.resetModules();
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:8080');

    const mod = await import('../client');

    expect(mod.apiFetch).toBeTypeOf('function');
  });
});
