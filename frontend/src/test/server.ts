import { setupServer } from 'msw/node';
import { appRoot } from '../config/basePath';

/** The mock backend; tests add handlers per case. Unhandled requests fail the test (see setup.ts). */
export const server = setupServer();

/** An absolute API URL under the test page's context path (http://localhost/graphify). */
export function apiUrl(path: string): string {
  return `${appRoot()}${path}`;
}
