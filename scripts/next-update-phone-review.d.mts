export interface PhoneReviewFixture {
  out: string;
  cookie: string;
  inject: (request: {method: string; url: string; headers: Record<string,string>; payload?: string}) => Promise<{statusCode:number;body:string;headers:Record<string,unknown>}>;
}
export function reviewPhone(fixture: PhoneReviewFixture): Promise<void>;
