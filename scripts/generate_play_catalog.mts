import { makePlayAIValueProduct } from '../services/api/src/ai-value-purchases.js';

// Draft public product data only. Play Console prices, merchant fees, tax and FX must be
// reviewed against the live payments profile before these rows enter a protected catalog.
const environment = process.argv[2];
if (environment !== 'test' && environment !== 'live') throw new Error('Specify test or live.');
const packs = [
  {name:'small',usd:700,nok:8900,aiUSD:369,aiNOK:3690,usCommission:210,usResidual:65,noTax:1780,noCommission:2136,noResidual:740},
  {name:'medium',usd:1300,nok:17900,aiUSD:766,aiNOK:7660,usCommission:390,usResidual:29,noTax:3580,noCommission:4296,noResidual:1215},
  {name:'large',usd:2000,nok:24900,aiUSD:1161,aiNOK:11610,usCommission:600,usResidual:64,noTax:4980,noCommission:5976,noResidual:592},
] as const;
const common = {provider:'play' as const,environment,merchant:'chat.mural.android',policyVersion:1,
  serviceFeeBasisPoints:1500,estimate:{nanoUSDPerMinute:'100000000',rateVersion:'play-20260929-estimate-v1'}};
const products = packs.flatMap(pack => {
  const providerProduct=`chat.mural.android.minutes.${pack.name}.v1`;
  return [
    makePlayAIValueProduct({...common,sku:`mural-play-usa-${pack.name}-v1`,providerProduct,
      aiValueMinor:pack.aiUSD,exchangeRate:{numerator:'1',denominator:'1',version:'play-20260929-usd-v1'},
      play:{currency:'usd',currencyExponent:2,unitTotalMinor:pack.usd,scheduleVersion:'play-20260929-price-v1',
        commissionBasisPoints:3000,taxMinor:0,commissionMinor:pack.usCommission,residualMinor:pack.usResidual}}),
    makePlayAIValueProduct({...common,sku:`mural-play-nor-${pack.name}-v1`,providerProduct,
      aiValueMinor:pack.aiNOK,exchangeRate:{numerator:'1',denominator:'10',version:'play-20260929-fx10-planning-v1'},
      play:{currency:'nok',currencyExponent:2,unitTotalMinor:pack.nok,scheduleVersion:'play-20260929-price-v1',
        commissionBasisPoints:3000,taxMinor:pack.noTax,commissionMinor:pack.noCommission,residualMinor:pack.noResidual}}),
  ];
});
process.stdout.write(JSON.stringify({version:2,products},null,2)+'\n');
