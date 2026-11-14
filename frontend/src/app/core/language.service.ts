import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { of, switchMap, tap } from 'rxjs';
import { Api } from './api.service';

@Injectable({ providedIn: 'root' })
export class Language {
  readonly translate = inject(TranslateService);
  private readonly api = inject(Api);
  private readonly document = inject(DOCUMENT);
  load(locale: string) {
    return this.translate
      .use(locale)
      .pipe(tap(() => (this.document.documentElement.lang = locale)));
  }
  change(locale: string) {
    const save = this.api.user()
      ? this.api.mutate<void>('PATCH', '/me/preferences', { locale })
      : of(undefined);
    return save.pipe(
      switchMap(() => this.load(locale)),
      tap(() => this.api.user.update((user) => (user ? { ...user, locale } : null))),
    );
  }
}
