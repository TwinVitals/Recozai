import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideTranslateService, TranslateLoader } from '@ngx-translate/core';
import { Language } from './core/language.service';
import { CatalogLoader } from './core/catalog';
import { provideRouter } from '@angular/router';
import Aura from '@primeuix/themes/aura';
import { providePrimeNG } from 'primeng/config';
import { UseStyle } from 'primeng/usestyle';
import { routes } from './app.routes';

declare global {
  interface Window {
    __RECOZAI_RUNTIME_CONFIG__?: {
      primeUiLicenseBase64?: string;
    };
  }
}

const primeUiLicenseBase64 = window.__RECOZAI_RUNTIME_CONFIG__?.primeUiLicenseBase64;
const primeUiLicense = primeUiLicenseBase64 ? atob(primeUiLicenseBase64) : undefined;

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(),
    {
      provide: UseStyle,
      useValue: {
        use: (css: string, options: { id?: string; name?: string } = {}) => ({
          id: options.id,
          name: options.name ?? 'style',
          el: null,
          css,
        }),
        remove: () => {},
        addShadowRoot: () => () => {},
      },
    },
    providePrimeNG({
      ...(primeUiLicense ? { license: primeUiLicense } : {}),
      theme: {
        preset: Aura,
        options: {
          darkModeSelector: false,
        },
      },
    }),
    provideTranslateService({
      lang: 'fr-SN',
      fallbackLang: 'fr-SN',
      loader: { provide: TranslateLoader, useClass: CatalogLoader },
    }),
    provideAppInitializer(() => {
      const language = inject(Language);
      return language.load('fr-SN');
    }),
  ],
};
