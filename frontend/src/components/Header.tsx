export function Header() {
  return (
    <header className="flex items-center justify-between mb-5 flex-wrap gap-3 pb-3.5 border-b border-border">
      <div className="flex items-center gap-4">
        <div className="w-[46px] h-[46px] bg-gradient-to-br from-violet to-teal rounded-[14px] flex items-center justify-center text-2xl shadow-glow-violet">
          🎙
        </div>
        <div>
          <h1 className="text-xl font-extrabold tracking-tight bg-gradient-to-r from-violet-light to-teal-light bg-clip-text text-transparent">
            MicMonitor
          </h1>
          <p className="text-[10px] text-text-dim mt-0.5 tracking-wider uppercase">
            Dashboard Control Center
          </p>
        </div>
      </div>
    </header>
  );
}
