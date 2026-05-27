// Root admin app — sidebar + topbar + section router.

const { useState: useStateApp, useEffect: useEffectApp } = React;

function App() {
  const [section, setSection] = useStateApp('rules'); // Land on Rules Engine — the headline section
  const [collapsed, setCollapsed] = useStateApp(false);
  const [role, setRole] = useStateApp(AD.USERS.damir);
  const [roleOpen, setRoleOpen] = useStateApp(false);
  const [contractOpen, setContractOpen] = useStateApp(false);
  const [cmdOpen, setCmdOpen] = useStateApp(false);

  // ⌘K palette
  useEffectApp(() => {
    const onKey = (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); setCmdOpen(true); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  // Update screen-label attr so review tools / mentioned-element know what's open
  const meta = AD.SECTIONS.find(s => s.id === section);

  const renderSection = () => {
    switch (section) {
      case 'dashboard':   return <DashboardSection/>;
      case 'promo':       return <PromoSection/>;
      case 'rules':       return <RulesSection/>;
      case 'screens':     return <ScreensSection/>;
      case 'pharmacies':  return <PharmaciesSection/>;
      case 'pharmacists': return <PharmacistsSection/>;
      case 'reconcile':   return <ReconcileSection/>;
      case 'ai_exam':     return <AIExamSection/>;
      case 'finance':     return <FinanceSection/>;
      case 'lift':        return <LiftSection/>;
      case 'lms':         return <LMSSection/>;
      case 'settings':    return <SettingsSection/>;
      default: return null;
    }
  };

  return (
    <ToastHost>
      <div className="h-screen flex bg-paper" data-screen-label={`Console / ${meta?.label || section}`} style={{minWidth:1280}}>
        <Sidebar
          active={section}
          onSelect={setSection}
          collapsed={collapsed}
          onToggle={() => setCollapsed(c => !c)}
          contractOpen={contractOpen}
          onContractOpen={() => setContractOpen(true)}
        />
        <div className="flex-1 flex flex-col min-w-0">
          <Topbar
            section={section}
            sectionLabel={meta?.label}
            role={role}
            onRoleSwitch={() => setRoleOpen(true)}
            onMenu={() => setCollapsed(c => !c)}
            onCommand={() => setCmdOpen(true)}
          />
          <main className="flex-1 overflow-auto scrollbar-thin px-6 pb-10">
            {renderSection()}
          </main>
        </div>

        <RoleSwitcher open={roleOpen} onClose={() => setRoleOpen(false)} current={role} onPick={setRole}/>
        <ContractModal open={contractOpen} onClose={() => setContractOpen(false)}/>
        <CommandPalette open={cmdOpen} onClose={() => setCmdOpen(false)} onNav={setSection}/>
      </div>
    </ToastHost>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App/>);
