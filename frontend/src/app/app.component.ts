import { Component, DestroyRef, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, switchMap } from 'rxjs';
import { Api, errorKey } from './core/api.service';
import { Language } from './core/language.service';
import { RecoveryWorkspaceService } from './core/recovery-workspace.service';

@Component({
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TranslatePipe],
  selector: 'app-root',
  templateUrl: './app.component.html',
})
export class App {
  readonly api = inject(Api);
  readonly language = inject(Language);
  readonly workspace = inject(RecoveryWorkspaceService);
  readonly error = signal('');
  readonly busy = signal(false);
  private readonly destroy = inject(DestroyRef);
  private readonly router = inject(Router);
  constructor() {
    this.api
      .refresh()
      .pipe(
        switchMap((user) => this.language.load(user?.locale ?? 'fr-SN')),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({ error: (error) => this.error.set(errorKey(error)) });
  }
  isLoginRoute() {
    return this.router.url.startsWith('/login');
  }
  changeLanguage(event: Event) {
    if (!(event.target instanceof HTMLSelectElement)) return;
    this.setLanguage(event.target.value);
  }
  setLanguage(locale: string) {
    this.error.set('');
    this.language
      .change(locale)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({ error: (error) => this.error.set(errorKey(error)) });
  }
  onGlobalSearch(event: Event) {
    if (!(event.target instanceof HTMLInputElement)) return;
    this.workspace.searchQuery.set(event.target.value);
  }
  submitGlobalSearch(query: string) {
    this.workspace.searchQuery.set(query);
    if (this.api.user()) {
      void this.router.navigate(['/denials']);
    }
  }
  logout() {
    this.busy.set(true);
    this.api
      .logout()
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: () => void this.router.navigate(['/login']),
        error: (error) => this.error.set(errorKey(error)),
      });
  }
}
