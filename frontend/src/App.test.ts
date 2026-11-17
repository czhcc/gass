import { describe, it, expect, vi, afterEach } from 'vitest';
import { fireEvent, render, screen, cleanup, waitFor } from '@testing-library/svelte';
import App from './App.svelte';
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
describe('studio', () => {
 it('switches language, logs in and logs out', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce({ok:false}).mockResolvedValueOnce({ok:true}).mockResolvedValueOnce({ok:true}));
  render(App);
  await waitFor(() => expect(screen.queryByText('加载中…')).toBeNull());
  await fireEvent.click(screen.getByText('English'));
  await fireEvent.input(screen.getByLabelText('Username'), {target:{value:'admin'}});
  await fireEvent.input(screen.getByLabelText('Password'), {target:{value:'123456'}});
  await fireEvent.click(screen.getByRole('button',{name:'Sign in'}));
  await waitFor(() => expect(screen.getByText('Sign out')).toBeTruthy());
  await fireEvent.click(screen.getByText('Sign out'));
  await waitFor(() => expect(screen.getByRole('button',{name:'Sign in'})).toBeTruthy());
 });
 it('shows invalid credentials', async () => {
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:false,status:401}));
  render(App);
  await waitFor(() => expect(screen.queryByText('加载中…')).toBeNull());
  await fireEvent.input(screen.getByLabelText('用户名'), {target:{value:'admin'}});
  await fireEvent.input(screen.getByLabelText('密码'), {target:{value:'bad'}});
  await fireEvent.click(screen.getByRole('button',{name:'登录'}));
  await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('用户名或密码错误'));
 });
});
