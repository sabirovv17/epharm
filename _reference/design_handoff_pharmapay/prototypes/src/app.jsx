// ────────────────────────────────────────────────────────────────────────────
// app.jsx — Root of the PharmaPay prototype
//
// Owns the entire screen state machine and the persistent form state
// (phone / SMS / ФИО / ИИН). Renders inside a scaled device frame whose
// chrome (status bar, bottom-nav, bezels) is platform-aware via the
// global `window.PLATFORM` flag set in PharmaPay.html ('ios') or
// PharmaPay (Android).html ('android').
//
// Screen flow:
//   WELCOME → HOME (unauthed) → PHONE → SMS → IIN (ФИО + ИИН) → HOME (authed)
//
// From HOME the user can tab into TRAINING (→ COURSE) or PROFILE, and at
// any moment open UPLOAD (→ CAMERA → SUCCESS) to send a receipt.
// «Войти» / «Чек tab» / «Загрузить чек» gate behind auth when needed.
//
// Authentication is opt-in: WELCOME drops the user straight into HOME so
// they can browse the catalog and learn what the app does before being
// asked for personal data. The state `authed` (bool) flips at the end of
// the IIN screen and back to false on Profile → «Выйти». Two UI states
// (HOME welcome banner / Profile login card) react to it.
//
// Bottom nav is only visible on the three tab destinations (HOME,
// TRAINING, PROFILE). Auth screens, camera and success run full-bleed.
// ────────────────────────────────────────────────────────────────────────────

// Root app: navigation state machine + iOS / Android device frame.
const { useState, useEffect, useMemo } = React;

const SCREENS = {
  WELCOME: 'welcome',
  WELCOME2: 'welcome2',
  PHONE: 'phone',
  SMS: 'sms',
  IIN: 'iin',
  HOME: 'home',
  TRAINING: 'training',
  COURSE: 'course',
  PROFILE: 'profile',
  CAMERA: 'camera',
  SUCCESS: 'success',
};

function App() {
  // route state
  const [screen, setScreen] = useState(SCREENS.WELCOME);
  const [activeTab, setActiveTab] = useState('home'); // home / training / profile
  // auth state — user can browse without registering
  const [authed, setAuthed] = useState(false);
  // form state
  const [phone, setPhone] = useState('');
  const [iin, setIin] = useState('');
  const [fio, setFio] = useState('');
  // home filters
  const [chip, setChip] = useState('all');
  const [sortIdx, setSortIdx] = useState(0);
  const [brandSel, setBrandSel] = useState([]);
  const [sortOpen, setSortOpen] = useState(false);
  const [brandsOpen, setBrandsOpen] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [activeCourse, setActiveCourse] = useState(null);
  const [activeProduct, setActiveProduct] = useState(null);

  // Device frame size — iPhone 14 (390×844) for iOS, Pixel 7 (412×892) for Android
  const isAndroid = window.PLATFORM === 'android';
  const FRAME_W = isAndroid ? 412 : 390;
  const FRAME_H = isAndroid ? 892 : 844;
  const [scale, setScale] = useState(1);
  useEffect(() => {
    const compute = () => {
      const padW = 40, padH = 40;
      const sw = (window.innerWidth - padW) / (FRAME_W + 40);
      const sh = (window.innerHeight - padH) / (FRAME_H + 40);
      setScale(Math.min(1.4, Math.max(0.6, Math.min(sw, sh))));
    };
    compute();
    window.addEventListener('resize', compute);
    return () => window.removeEventListener('resize', compute);
  }, []);

  // tab handler — when on a tab, returning to it resets sub-screen
  const goTab = (t) => {
    setActiveTab(t);
    if (t === 'home') setScreen(SCREENS.HOME);
    if (t === 'training') setScreen(SCREENS.TRAINING);
    if (t === 'profile') setScreen(SCREENS.PROFILE);
    setActiveCourse(null);
  };

  // bottom nav visible on these tab screens
  const showNav = [SCREENS.HOME, SCREENS.TRAINING, SCREENS.PROFILE].includes(screen);

  return (
    <div className="w-full h-full flex items-center justify-center" style={{background: 'radial-gradient(circle at 30% 20%, #2a2c3b 0%, #0c0d18 70%)'}}>
      {/* scaled device */}
      <div style={{ transform: `scale(${scale})`, transformOrigin: 'center center' }}>
        <DeviceFrame width={FRAME_W} height={FRAME_H}>
          <div className="relative w-full h-full overflow-hidden">
            {/* Onboarding → straight into the browseable Home (no forced login) */}
            {screen === SCREENS.WELCOME && (
              <WelcomeFlow onContinue={()=>{ setActiveTab('home'); setScreen(SCREENS.HOME); }}/>
            )}
            {screen === SCREENS.PHONE && (
              <PhoneScreen phone={phone} setPhone={setPhone} onContinue={()=>setScreen(SCREENS.SMS)} onBack={()=>setScreen(SCREENS.HOME)}/>
            )}
            {screen === SCREENS.SMS && (
              <SmsScreen phone={phone} onContinue={()=>setScreen(SCREENS.IIN)} onBack={()=>setScreen(SCREENS.PHONE)}/>
            )}
            {screen === SCREENS.IIN && (
              <IinScreen fio={fio} setFio={setFio} iin={iin} setIin={setIin} onContinue={()=>{ setAuthed(true); setActiveTab('home'); setScreen(SCREENS.HOME); }}/>
            )}
            {screen === SCREENS.HOME && (
              <HomeScreen
                authed={authed}
                onLogin={()=>setScreen(SCREENS.PHONE)}
                onUpload={()=> authed ? setUploadOpen(true) : setScreen(SCREENS.PHONE)}
                onHistory={()=>{}}
                openSort={()=>setSortOpen(true)}
                openBrands={()=>setBrandsOpen(true)}
                activeChip={chip} setActiveChip={setChip}
                sortIdx={sortIdx} brandSel={brandSel}
                onProduct={(p)=>setActiveProduct(p)}
              />
            )}
            {screen === SCREENS.TRAINING && (
              <TrainingScreen onOpenCourse={(c)=>{ setActiveCourse(c); setScreen(SCREENS.COURSE); }}/>
            )}
            {screen === SCREENS.COURSE && (
              <CourseDetail course={activeCourse} onBack={()=>setScreen(SCREENS.TRAINING)} onComplete={()=>setScreen(SCREENS.TRAINING)}/>
            )}
            {screen === SCREENS.PROFILE && (
              <ProfileScreen
                authed={authed}
                fio={fio}
                phone={phone}
                onLogin={()=>setScreen(SCREENS.PHONE)}
                onLogout={()=>{
                  setAuthed(false);
                  setPhone('');
                  setIin('');
                  setFio('');
                  setActiveTab('home');
                  setScreen(SCREENS.HOME);
                }}
              />
            )}
            {screen === SCREENS.CAMERA && (
              <CameraScreen onCapture={()=>setScreen(SCREENS.SUCCESS)} onBack={()=>setScreen(SCREENS.HOME)}/>
            )}
            {screen === SCREENS.SUCCESS && (
              <SuccessScreen
                onHistory={()=>{ setActiveTab('profile'); setScreen(SCREENS.PROFILE); }}
                onAgain={()=>setScreen(SCREENS.CAMERA)}
              />
            )}

            {/* Bottom nav appears only on tab screens */}
            {showNav && (
              <BottomNav
                tab={activeTab}
                onTab={goTab}
                onReceipt={()=> authed ? setUploadOpen(true) : setScreen(SCREENS.PHONE)}
              />
            )}

            {/* Modals / sheets — home only */}
            <SortSheet open={sortOpen} onClose={()=>setSortOpen(false)} sortIdx={sortIdx} setSortIdx={setSortIdx}/>
            <BrandSheet open={brandsOpen} onClose={()=>setBrandsOpen(false)} brandSel={brandSel} setBrandSel={setBrandSel}/>
            <UploadPrompt open={uploadOpen} onClose={()=>setUploadOpen(false)} onTake={()=>{ setUploadOpen(false); setScreen(SCREENS.CAMERA); }}/>
            <ProductDetailSheet open={!!activeProduct} onClose={()=>setActiveProduct(null)} product={activeProduct}/>
          </div>
        </DeviceFrame>
      </div>
    </div>
  );
}

// Device frame — picks iOS or Android chrome based on window.PLATFORM.
function DeviceFrame(props) {
  return window.PLATFORM === 'android' ? <AndroidDeviceFrame {...props}/> : <IOSDeviceFrame {...props}/>;
}

// iPhone bezel + Dynamic Island.
function IOSDeviceFrame({ width, height, children }) {
  return (
    <div className="relative" style={{
      width: width + 16, height: height + 16,
      borderRadius: 56,
      background: 'linear-gradient(180deg, #2a2d3a 0%, #0e0f1a 100%)',
      padding: 8,
      boxShadow: '0 50px 100px rgba(0,0,0,0.6), 0 0 0 1px rgba(255,255,255,0.05) inset, 0 0 0 2px rgba(0,0,0,0.6)',
    }}>
      <div className="relative overflow-hidden" style={{
        width, height, borderRadius: 48, background: '#fff',
      }}>
        {/* dynamic island */}
        <div className="absolute left-1/2 -translate-x-1/2 top-2 w-[110px] h-[34px] bg-black rounded-full z-50"/>
        {children}
      </div>
    </div>
  );
}

// Pixel-style bezel + centred hole-punch camera.
function AndroidDeviceFrame({ width, height, children }) {
  return (
    <div className="relative" style={{
      width: width + 14, height: height + 14,
      borderRadius: 48,
      background: 'linear-gradient(180deg, #353740 0%, #0d0e16 100%)',
      padding: 7,
      boxShadow: '0 50px 100px rgba(0,0,0,0.6), 0 0 0 1px rgba(255,255,255,0.05) inset, 0 0 0 2px rgba(0,0,0,0.6)',
    }}>
      <div className="relative overflow-hidden" style={{
        width, height, borderRadius: 42, background: '#fff',
      }}>
        {/* hole-punch camera (centred at top) */}
        <div className="absolute left-1/2 -translate-x-1/2 top-2 z-50" style={{
          width: 14, height: 14, borderRadius: '50%', background: '#0a0b14',
          boxShadow: 'inset 0 0 0 1px rgba(255,255,255,0.05)',
        }}/>
        {children}
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App/>);
