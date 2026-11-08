export function CardSkeleton() {
  return (
    <div className="card overflow-hidden" role="status" aria-label="Loading">
      <div className="skeleton h-32 rounded-none" />
      <div className="space-y-3 p-4">
        <div className="skeleton h-4 w-3/4" />
        <div className="skeleton h-3 w-1/2" />
        <div className="skeleton h-2 w-full" />
        <div className="flex justify-between">
          <div className="skeleton h-3 w-24" />
          <div className="skeleton h-3 w-16" />
        </div>
      </div>
    </div>
  );
}

export function CardGridSkeleton({ count = 6 }: { count?: number }) {
  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3" aria-busy="true">
      {Array.from({ length: count }).map((_, index) => (
        <CardSkeleton key={index} />
      ))}
    </div>
  );
}

export function RowSkeleton({ count = 3 }: { count?: number }) {
  return (
    <div className="space-y-2" aria-busy="true">
      {Array.from({ length: count }).map((_, index) => (
        <div key={index} className="skeleton h-16 rounded-md" />
      ))}
    </div>
  );
}

export function PageSkeleton() {
  return (
    <div className="space-y-5" aria-busy="true" role="status" aria-label="Loading page">
      <div className="skeleton h-7 w-48" />
      <div className="skeleton h-4 w-72" />
      <div className="skeleton h-44 rounded-lg" />
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="skeleton h-40 rounded-lg" />
        <div className="skeleton h-40 rounded-lg" />
      </div>
    </div>
  );
}

export function ChatSkeleton() {
  return (
    <div className="space-y-3 p-4" aria-busy="true" role="status" aria-label="Loading conversation">
      <div className="skeleton h-12 w-2/3 rounded-2xl" />
      <div className="ml-auto skeleton h-12 w-1/2 rounded-2xl" />
      <div className="skeleton h-12 w-3/5 rounded-2xl" />
      <div className="ml-auto skeleton h-12 w-2/5 rounded-2xl" />
    </div>
  );
}
